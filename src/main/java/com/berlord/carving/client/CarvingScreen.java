package com.berlord.carving.client;

import com.berlord.carving.ArmorKind;
import com.berlord.carving.Carving;
import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.ToolKind;
import com.berlord.carving.net.CarveResultPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.berlord.carving.client.ShapeLibrary.CELLS;
import static com.berlord.carving.client.ShapeLibrary.GRID;

/**
 * Client-side carving on the 16x16 canvas. Per (slate, tool) a deterministic seed fixes a random
 * rotation + placement + one of five visual "debuff" effects, locked so re-selecting a tool never
 * re-rolls. Cells are the raw block texture; only the form-line reveals the shape. You click/drag to
 * carve; severed chunks drop; lifting the mouse, three errors, or leaving all cost.
 */
public class CarvingScreen extends Screen {
    private static final int CELL = 11;
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 280;
    private static final int TAB_W = 36;
    private static final int TAB_H = 20;
    private static final int RUIN_AT = 3;
    private static final int LINE = 0x9A9A9A; // all borders, every variant

    private static final int FX_FLUID = 0;   // "fluid": one traced+smoothed undulating perimeter loop
    private static final int FX_NOISE = 1;   // "noise": all edges (incl. internal) jitter in parallel
    private static final int FX_DYNO = 2;    // "dyno": perimeter outline breathes/pans; background static
    private static final int FX_COMBO = 3;   // bg zoom + shape shake + blur on/off, all out of sync
    private static final int FX_COUNT = 3;   // FX_FLUID, FX_NOISE, FX_DYNO (FX_COMBO removed)

    private static final double AMP = 0.40 * CELL;

    // sample offsets for the box-blur (unit disc), scaled by radius
    private static final double[][] BLUR_OFF = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0.7, 0.7}, {-0.7, 0.7}, {0.7, -0.7}, {-0.7, -0.7},
            {0.5, 0}, {-0.5, 0}, {0, 0.5}, {0, -0.5}
    };

    private final CarvingMaterial material;
    private final boolean armor;
    private final InteractionHand hand;
    private final long sessionSeed = new Random().nextLong();
    private final Random pitchRnd = new Random();

    private int selected = 0;
    private final boolean[] present = new boolean[CELLS];
    private int[] tLevels = new int[CELLS];
    private final boolean[] keep = new boolean[CELLS];
    private int anchor = -1;
    private int effect = FX_NOISE;
    private double centroidX, centroidY;
    private final List<Edge> edges = new ArrayList<>();   // cell-edge segments (for non-wave variants)
    private final List<double[]> waveLoops = new ArrayList<>(); // smoothed perimeter loops, corner-space

    private int slips = 0;
    private int releaseErrors = 0;
    private boolean committed = false;
    private boolean finished = false;
    private boolean transformBuilt = false;
    private long lastSoundMs = 0;

    private int left, top, gridLeft, gridTop, gridSize, tabY, tabAreaLeft;
    private TextureAtlasSprite blockSprite;
    private ResourceLocation directTex; // bundled surface when a material's block texture comes from another mod

    public CarvingScreen(CarvingMaterial material, boolean armor, InteractionHand hand) {
        super(Component.translatable("berlords_carving.screen.title"));
        this.material = material;
        this.armor = armor;
        this.hand = hand;
        java.util.Arrays.fill(present, true);
    }

    private int kindCount() {
        return armor ? ArmorKind.values().length : ToolKind.values().length;
    }

    private ItemStack kindIcon(int k) {
        return Carving.iconStack(material, armor, k);
    }

    private static String bgTexture(CarvingMaterial m) {
        return switch (m) {
            case WOOD -> "minecraft:block/oak_planks";
            case BONE -> "minecraft:block/bone_block_side";
            case LEATHER -> "minecraft:block/packed_mud";
            default -> "minecraft:block/stone";
        };
    }

    private static SoundType bgSound(CarvingMaterial m) {
        return switch (m) {
            case WOOD -> SoundType.WOOD;
            case FLINT -> SoundType.GRAVEL;
            case BONE -> SoundType.BONE_BLOCK;
            case LEATHER -> SoundType.WOOL;
            default -> SoundType.STONE;
        };
    }

    private record Edge(boolean vert, int fixed, int s, int e, boolean silh, int nrm, double ang) {
    }

    @Override
    protected void init() {
        this.gridSize = GRID * CELL;
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
        this.tabAreaLeft = left + (PANEL_W - kindCount() * TAB_W) / 2;
        this.tabY = top + 24;
        this.gridLeft = left + (PANEL_W - gridSize) / 2;
        this.gridTop = top + 50;
        if (material == CarvingMaterial.FLINT) {
            // anvilcraft's flint_block texture is absent without that mod -> blit our bundled copy
            this.directTex = ResourceLocation.fromNamespaceAndPath(Carving.MODID, "textures/block/flint_surface.png");
        } else {
            this.directTex = null;
            this.blockSprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.parse(bgTexture(material)));
        }
        if (!transformBuilt) {
            buildTransform();
            transformBuilt = true;
        } else {
            rebuildEdges();
        }
    }

    /** Per (slate, tool): deterministic rotation + flip + placement + effect. Re-selecting never re-rolls. */
    private void buildTransform() {
        int[] base = ShapeLibrary.levels(Carving.shapeKey(material, armor, selected));
        int minR = GRID, maxR = -1, minC = GRID, maxC = -1;
        for (int i = 0; i < CELLS; i++) {
            if (base[i] >= 0) {
                minR = Math.min(minR, i / GRID);
                maxR = Math.max(maxR, i / GRID);
                minC = Math.min(minC, i % GRID);
                maxC = Math.max(maxC, i % GRID);
            }
        }
        int bh = maxR - minR + 1, bw = maxC - minC + 1;

        long seed = sessionSeed ^ ((long) selected * 0x9E3779B97F4A7C15L); // per-kind, deterministic
        Random r = new Random(seed);
        boolean flipX = r.nextBoolean(), flipY = r.nextBoolean();
        int rot = r.nextInt(4);
        this.effect = r.nextInt(FX_COUNT);
        int rh = (rot % 2 == 0) ? bh : bw, rw = (rot % 2 == 0) ? bw : bh;
        int offR = r.nextInt(Math.max(1, GRID - rh + 1));
        int offC = r.nextInt(Math.max(1, GRID - rw + 1));

        tLevels = new int[CELLS];
        java.util.Arrays.fill(tLevels, -1);
        for (int i = 0; i < CELLS; i++) {
            if (base[i] < 0) {
                continue;
            }
            int sr = (i / GRID) - minR, sc = (i % GRID) - minC, rr, rc;
            switch (rot) {
                case 1 -> { rr = sc; rc = bh - 1 - sr; }
                case 2 -> { rr = bh - 1 - sr; rc = bw - 1 - sc; }
                case 3 -> { rr = bw - 1 - sc; rc = sr; }
                default -> { rr = sr; rc = sc; }
            }
            if (flipY) {
                rr = rh - 1 - rr;
            }
            if (flipX) {
                rc = rw - 1 - rc;
            }
            int tr = offR + rr, tc = offC + rc;
            if (tr >= 0 && tr < GRID && tc >= 0 && tc < GRID) {
                tLevels[tr * GRID + tc] = base[i];
            }
        }
        long sum = 0, sx = 0, sy = 0;
        for (int i = 0; i < CELLS; i++) {
            keep[i] = tLevels[i] >= 0;
            if (keep[i]) {
                sum++;
                sx += i % GRID;
                sy += i / GRID;
            }
        }
        anchor = anchorCell();
        centroidX = sum > 0 ? (double) sx / sum : GRID / 2.0;
        centroidY = sum > 0 ? (double) sy / sum : GRID / 2.0;
        traceLoops();
        rebuildEdges();
    }

    private void rebuildEdges() {
        edges.clear();
        double cx = gridLeft + (centroidX + 0.5) * CELL;
        double cy = gridTop + (centroidY + 0.5) * CELL;
        for (int i = 0; i < CELLS; i++) {
            int r = i / GRID, c = i % GRID, lv = tLevels[i];
            int x = gridLeft + c * CELL, y = gridTop + r * CELL;
            int rn = (c < GRID - 1) ? tLevels[i + 1] : -1;
            if (lv != rn) {
                boolean silh = lv < 0 || rn < 0;
                int nrm = (lv >= 0 && rn < 0) ? 1 : (lv < 0 ? -1 : 0);
                edges.add(new Edge(true, x + CELL, y, y + CELL, silh, nrm, Math.atan2((y + CELL / 2.0) - cy, (x + CELL) - cx)));
            }
            int dn = (r < GRID - 1) ? tLevels[i + GRID] : -1;
            if (lv != dn) {
                boolean silh = lv < 0 || dn < 0;
                int nrm = (lv >= 0 && dn < 0) ? 1 : (lv < 0 ? -1 : 0);
                edges.add(new Edge(false, y + CELL, x, x + CELL, silh, nrm, Math.atan2((y + CELL) - cy, (x + CELL / 2.0) - cx)));
            }
            if (c == 0 && lv >= 0) {
                edges.add(new Edge(true, x, y, y + CELL, true, -1, 0));
            }
            if (r == 0 && lv >= 0) {
                edges.add(new Edge(false, y, x, x + CELL, true, -1, 0));
            }
        }
    }

    // ---- perimeter tracing (for FX_WAVE: one continuous fluid line) -------

    private void traceLoops() {
        waveLoops.clear();
        int n = GRID + 1; // corners per row
        // directed boundary half-edges {src, dst, dirX, dirY, used}; interior kept on the left (CCW)
        List<int[]> all = new ArrayList<>();
        Map<Integer, List<int[]>> out = new HashMap<>();
        for (int i = 0; i < CELLS; i++) {
            if (!keep[i]) {
                continue;
            }
            int r = i / GRID, c = i % GRID;
            boolean up = r > 0 && keep[i - GRID];
            boolean dn = r < GRID - 1 && keep[i + GRID];
            boolean lf = c > 0 && keep[i - 1];
            boolean rt = c < GRID - 1 && keep[i + 1];
            if (!dn) addEdge(all, out, c + (r + 1) * n, (c + 1) + (r + 1) * n, 1, 0);   // bottom, RIGHT
            if (!rt) addEdge(all, out, (c + 1) + (r + 1) * n, (c + 1) + r * n, 0, -1);  // right, UP
            if (!up) addEdge(all, out, (c + 1) + r * n, c + r * n, -1, 0);              // top, LEFT
            if (!lf) addEdge(all, out, c + r * n, c + (r + 1) * n, 0, 1);               // left, DOWN
        }
        for (int[] e0 : all) {
            if (e0[4] == 1) {
                continue;
            }
            List<int[]> pts = new ArrayList<>();
            int[] e = e0;
            int guard = 0;
            while (e != null && e[4] == 0 && guard++ < CELLS * 8) {
                e[4] = 1;
                pts.add(new int[]{e[0] % n, e[0] / n});
                List<int[]> outs = out.get(e[1]);
                int[] nextE = null;
                if (outs != null) {
                    int wantX = e[3], wantY = -e[2]; // left turn: keeps the two chains separate at a pinch
                    for (int[] cand : outs) {
                        if (cand[4] == 0 && cand[2] == wantX && cand[3] == wantY) {
                            nextE = cand;
                            break;
                        }
                    }
                    if (nextE == null) {
                        for (int[] cand : outs) {
                            if (cand[4] == 0) {
                                nextE = cand;
                                break;
                            }
                        }
                    }
                }
                e = nextE;
            }
            if (pts.size() >= 4) {
                waveLoops.add(smoothLoop(pts));
            }
        }
    }

    private void addEdge(List<int[]> all, Map<Integer, List<int[]>> out, int src, int dst, int dx, int dy) {
        int[] e = {src, dst, dx, dy, 0};
        all.add(e);
        out.computeIfAbsent(src, k -> new ArrayList<>()).add(e);
    }

    /** Chaikin corner-cutting (2 iterations) on a closed loop -> rounded points (corner-space, flat x,y). */
    private double[] smoothLoop(List<int[]> pts) {
        List<double[]> cur = new ArrayList<>();
        for (int[] p : pts) {
            cur.add(new double[]{p[0], p[1]});
        }
        for (int it = 0; it < 2; it++) {
            List<double[]> nx = new ArrayList<>();
            int m = cur.size();
            for (int i = 0; i < m; i++) {
                double[] a = cur.get(i), b = cur.get((i + 1) % m);
                nx.add(new double[]{0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]});
                nx.add(new double[]{0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]});
            }
            cur = nx;
        }
        double[] out = new double[cur.size() * 2];
        for (int i = 0; i < cur.size(); i++) {
            out[i * 2] = cur.get(i)[0];
            out[i * 2 + 1] = cur.get(i)[1];
        }
        return out;
    }

    private int tabX(int k) {
        return tabAreaLeft + k * TAB_W;
    }

    private int cellAt(int mx, int my) {
        if (mx < gridLeft || my < gridTop) {
            return -1;
        }
        int col = (mx - gridLeft) / CELL, row = (my - gridTop) / CELL;
        return (col < 0 || col >= GRID || row < 0 || row >= GRID) ? -1 : row * GRID + col;
    }

    private int totalErrors() {
        return countFlaws() + releaseErrors;
    }

    // ---- carving logic ----------------------------------------------------

    private int flood(int start, boolean[] vis) {
        int[] stack = new int[CELLS];
        int sp = 0;
        stack[sp++] = start;
        vis[start] = true;
        int count = 0;
        while (sp > 0) {
            int c = stack[--sp];
            count++;
            int row = c / GRID, col = c % GRID;
            int[] nb = {col > 0 ? c - 1 : -1, col < GRID - 1 ? c + 1 : -1,
                    row > 0 ? c - GRID : -1, row < GRID - 1 ? c + GRID : -1};
            for (int nn : nb) {
                if (nn >= 0 && present[nn] && !vis[nn]) {
                    vis[nn] = true;
                    stack[sp++] = nn;
                }
            }
        }
        return count;
    }

    private int anchorCell() {
        int best = -1, bestRow = -1, bestDist = 99;
        for (int i = 0; i < CELLS; i++) {
            if (keep[i]) {
                int row = i / GRID, dist = Math.abs((i % GRID) - GRID / 2);
                if (row > bestRow || (row == bestRow && dist < bestDist)) {
                    best = i;
                    bestRow = row;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    private void pruneDisconnected() {
        int start = -1;
        if (anchor >= 0 && present[anchor]) {
            start = anchor;
        } else {
            int bestRow = -1, bestDist = 99;
            for (int i = 0; i < CELLS; i++) {
                if (keep[i] && present[i]) {
                    int row = i / GRID, dist = Math.abs((i % GRID) - GRID / 2);
                    if (row > bestRow || (row == bestRow && dist < bestDist)) {
                        start = i;
                        bestRow = row;
                        bestDist = dist;
                    }
                }
            }
        }
        if (start < 0) {
            boolean[] seen = new boolean[CELLS];
            int bestSize = 0;
            for (int i = 0; i < CELLS; i++) {
                if (present[i] && !seen[i]) {
                    boolean[] tmp = new boolean[CELLS];
                    int sz = flood(i, tmp);
                    for (int j = 0; j < CELLS; j++) {
                        if (tmp[j]) {
                            seen[j] = true;
                        }
                    }
                    if (sz > bestSize) {
                        bestSize = sz;
                        start = i;
                    }
                }
            }
        }
        if (start < 0) {
            return;
        }
        boolean[] vis = new boolean[CELLS];
        flood(start, vis);
        for (int i = 0; i < CELLS; i++) {
            if (present[i] && !vis[i]) {
                present[i] = false;
            }
        }
    }

    private int countFlaws() {
        int f = 0;
        for (int i = 0; i < CELLS; i++) {
            if (keep[i] && !present[i]) {
                f++;
            }
        }
        return f;
    }

    private boolean isComplete() {
        for (int i = 0; i < CELLS; i++) {
            if (!keep[i] && present[i]) {
                return false;
            }
        }
        return true;
    }

    private void carve(int i) {
        if (finished || !present[i]) {
            return;
        }
        present[i] = false;
        committed = true;
        long now = Util.getMillis();
        if (now - lastSoundMs >= 35) {
            lastSoundMs = now;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    bgSound(material).getPlaceSound(), 0.8F + pitchRnd.nextFloat() * 0.4F, 0.35F));
        }
        pruneDisconnected();
        if (totalErrors() >= RUIN_AT) {
            finish(totalErrors());
            return;
        }
        if (isComplete()) {
            finish(totalErrors());
        }
    }

    private void finish(int flaws) {
        if (finished) {
            return;
        }
        finished = true;
        PacketDistributor.sendToServer(new CarveResultPayload(
                material.ordinal(), armor, selected, flaws, hand == InteractionHand.MAIN_HAND));
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void onClose() {
        if (!finished) {
            finished = true;
            PacketDistributor.sendToServer(new CarveResultPayload(
                    material.ordinal(), armor, selected, RUIN_AT, hand == InteractionHand.MAIN_HAND));
        }
        super.onClose();
    }

    // ---- input ------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !finished) {
            if (!committed) {
                for (int k = 0; k < kindCount(); k++) {
                    int tx = tabX(k);
                    if (mx >= tx + 1 && mx < tx + TAB_W - 1 && my >= tabY && my < tabY + TAB_H) {
                        if (k != selected) {
                            selected = k;
                            buildTransform();
                        }
                        return true;
                    }
                }
            }
            int i = cellAt((int) mx, (int) my);
            if (i >= 0 && present[i]) {
                carve(i);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && !finished) {
            double px = mx - dx, py = my - dy;
            int steps = (int) (Math.max(Math.abs(dx), Math.abs(dy)) / 4) + 1;
            for (int s = 0; s <= steps && !finished; s++) {
                double tt = (double) s / steps;
                int i = cellAt((int) (px + (mx - px) * tt), (int) (py + (my - py) * tt));
                if (i >= 0 && present[i]) {
                    carve(i);
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && committed && !finished) {
            releaseErrors++;
            if (totalErrors() >= RUIN_AT) {
                finish(totalErrors());
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    // ---- line helpers -----------------------------------------------------

    private double frac(double v) {
        return v - Math.floor(v);
    }

    private double wobble(int seed, double t) {
        double p1 = frac(seed * 0.6180339887) * (2 * Math.PI);
        double p2 = frac(seed * 0.7548776662 + 0.37) * (2 * Math.PI);
        return AMP * (0.6 * Math.sin((2 * Math.PI / 2.5) * t + p1) + 0.4 * Math.sin((2 * Math.PI / 3.7) * t + p2));
    }

    private void aaH(GuiGraphics g, int xL, int xR, double yf, int alpha) {
        int yi = (int) Math.floor(yf);
        double f = yf - yi;
        int a0 = (int) Math.round(alpha * (1 - f)), a1 = (int) Math.round(alpha * f);
        if (a0 > 0) {
            g.fill(xL, yi, xR, yi + 1, (a0 << 24) | LINE);
        }
        if (a1 > 0) {
            g.fill(xL, yi + 1, xR, yi + 2, (a1 << 24) | LINE);
        }
    }

    private void aaV(GuiGraphics g, int yT, int yB, double xf, int alpha) {
        int xi = (int) Math.floor(xf);
        double f = xf - xi;
        int a0 = (int) Math.round(alpha * (1 - f)), a1 = (int) Math.round(alpha * f);
        if (a0 > 0) {
            g.fill(xi, yT, xi + 1, yB, (a0 << 24) | LINE);
        }
        if (a1 > 0) {
            g.fill(xi + 1, yT, xi + 2, yB, (a1 << 24) | LINE);
        }
    }

    private void drawEdge(GuiGraphics g, Edge e, double off, int alpha) {
        if (e.vert()) {
            aaV(g, e.s(), e.e(), e.fixed() + off, alpha);
        } else {
            aaH(g, e.s(), e.e(), e.fixed() + off, alpha);
        }
    }

    private void plotLine(GuiGraphics g, double x0, double y0, double x1, double y1, int col) {
        double dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps < 1) {
            steps = 1;
        }
        for (int s = 0; s <= steps; s++) {
            double tt = (double) s / steps;
            int px = (int) Math.round(x0 + dx * tt), py = (int) Math.round(y0 + dy * tt);
            g.fill(px, py, px + 1, py + 1, col);
        }
    }

    private void renderWave(GuiGraphics g, double t) {
        int col = (220 << 24) | LINE;
        for (double[] loop : waveLoops) {
            int m = loop.length / 2;
            if (m < 3) {
                continue;
            }
            double[] sx = new double[m], sy = new double[m];
            for (int i = 0; i < m; i++) {
                sx[i] = gridLeft + loop[i * 2] * CELL;
                sy[i] = gridTop + loop[i * 2 + 1] * CELL;
            }
            double len = 0;
            double[] cum = new double[m];
            for (int i = 0; i < m; i++) {
                cum[i] = len;
                int j = (i + 1) % m;
                len += Math.hypot(sx[j] - sx[i], sy[j] - sy[i]);
            }
            if (len < 1e-6) {
                continue;
            }
            double[] dx = new double[m], dy = new double[m];
            for (int i = 0; i < m; i++) {
                int ip = (i - 1 + m) % m, in = (i + 1) % m;
                double tx = sx[in] - sx[ip], ty = sy[in] - sy[ip];
                double tl = Math.hypot(tx, ty);
                if (tl < 1e-6) {
                    tl = 1;
                }
                double nx = -ty / tl, ny = tx / tl;
                double d = AMP * Math.sin(3.0 * 2 * Math.PI * cum[i] / len - (2 * Math.PI / 3.0) * t);
                dx[i] = sx[i] + nx * d;
                dy[i] = sy[i] + ny * d;
            }
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                plotLine(g, dx[i], dy[i], dx[j], dy[j], col);
            }
        }
    }

    private void renderEffectLines(GuiGraphics g, double t) {
        switch (effect) {
            case FX_FLUID -> renderWave(g, t);
            case FX_DYNO -> {
                double s = 1.0 + 0.08 * Math.sin((2 * Math.PI / 2.2) * t);
                double pan = 0.6 * CELL * Math.sin((2 * Math.PI / 2.6) * t);
                double cx = gridLeft + (centroidX + 0.5) * CELL, cy = gridTop + (centroidY + 0.5) * CELL;
                g.pose().pushPose();
                g.pose().translate(cx + pan, cy, 0);
                g.pose().scale((float) s, (float) s, 1.0F);
                g.pose().translate(-cx, -cy, 0);
                for (Edge e : edges) {
                    if (e.silh()) {
                        drawEdge(g, e, 0, 215);
                    }
                }
                g.pose().popPose();
            }
            default -> { // FX_NOISE
                int idx = 0;
                for (Edge e : edges) {
                    drawEdge(g, e, wobble(idx * 4 + (e.vert() ? 0 : 1), t), e.silh() ? 215 : 110);
                    idx++;
                }
            }
        }
    }

    /** Blit the carving surface, scaled to the grid: bundled texture (flint) or the block-atlas sprite. */
    private void blitSurface(GuiGraphics g, int x, int y) {
        if (directTex != null) {
            g.blit(directTex, x, y, gridSize, gridSize, 0F, 0F, 16, 16, 16, 16);
        } else {
            g.blit(x, y, 0, gridSize, gridSize, blockSprite);
        }
    }

    /**
     * Visible box blur of the (already pose-transformed) block: alpha-blended offset re-samples.
     * Blend MUST be explicitly enabled or the sprite blit ignores setColor's alpha and renders opaque
     * (the last sample just overwrites = no blur). Left enabled afterwards (the form-line alpha fills want it).
     */
    private void blurBlock(GuiGraphics g, double intensity) {
        double radius = 0.5 + 7.0 * intensity;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int k = 0; k < BLUR_OFF.length; k++) {
            int ox = (int) Math.round(BLUR_OFF[k][0] * radius);
            int oy = (int) Math.round(BLUR_OFF[k][1] * radius);
            g.setColor(1, 1, 1, 1.0F / (k + 1));
            blitSurface(g, gridLeft + ox, gridTop + oy);
        }
        g.setColor(1, 1, 1, 1);
    }

    // ---- render -----------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        double t = Util.getMillis() / 1000.0;

        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, 0xFF000000);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFFC6C6C6);
        g.fill(left, top, left + PANEL_W, top + 2, 0xFFFFFFFF);
        g.fill(left, top, left + 2, top + PANEL_H, 0xFFFFFFFF);
        g.fill(left, top + PANEL_H - 2, left + PANEL_W, top + PANEL_H, 0xFF555555);
        g.fill(left + PANEL_W - 2, top, left + PANEL_W, top + PANEL_H, 0xFF555555);
        g.drawString(this.font, this.title, left + PANEL_W / 2 - this.font.width(this.title) / 2, top + 8, 0xFF222222, false);

        ItemStack hoveredTab = ItemStack.EMPTY;
        for (int k = 0; k < kindCount(); k++) {
            int tx = tabX(k);
            boolean sel = k == selected;
            ItemStack icon = kindIcon(k);
            boolean hov = mouseX >= tx + 1 && mouseX < tx + TAB_W - 1 && mouseY >= tabY && mouseY < tabY + TAB_H;
            g.fill(tx + 1, tabY, tx + TAB_W - 1, tabY + TAB_H, sel ? 0xFFAFAFAF : (hov ? 0xFFBDBDBD : 0xFF8B8B8B));
            g.renderOutline(tx + 1, tabY, TAB_W - 2, TAB_H, sel ? 0xFF303030 : 0xFF6E6E6E);
            g.renderItem(icon, tx + (TAB_W - 16) / 2, tabY + (TAB_H - 16) / 2);
            if (committed && !sel) {
                g.fill(tx + 1, tabY, tx + TAB_W - 1, tabY + TAB_H, 0x80AAAAAA);
            }
            if (hov) {
                hoveredTab = icon;
            }
        }

        slips = totalErrors();
        int hovered = cellAt(mouseX, mouseY);
        g.fill(gridLeft - 2, gridTop - 2, gridLeft + gridSize + 2, gridTop + gridSize + 2, 0xFF373737);

        // block surface (each cell = raw block pixel; only the line gives the shape)
        blitSurface(g, gridLeft, gridTop);

        // carve state (recesses only; no wash on present cells)
        for (int i = 0; i < CELLS; i++) {
            int x = gridLeft + (i % GRID) * CELL, y = gridTop + (i / GRID) * CELL;
            if (!present[i]) {
                g.fill(x, y, x + CELL, y + CELL, 0xFF1A1712);
                g.fill(x, y, x + CELL, y + 1, 0xFF000000);
                g.fill(x, y, x + 1, y + CELL, 0xFF000000);
            }
            if (i == hovered && present[i] && !finished) {
                g.fill(x, y, x + CELL, y + CELL, 0x30FFFFFF);
            }
        }

        g.enableScissor(gridLeft, gridTop, gridLeft + gridSize, gridTop + gridSize);
        renderEffectLines(g, t);
        g.disableScissor();

        int footerY = gridTop + gridSize + 8;
        g.drawString(this.font, Component.translatable("berlords_carving.screen.slips", slips),
                left + 10, footerY, 0xFF202020, false);
        int pipX = left + PANEL_W - 10 - RUIN_AT * 11;
        for (int k = 0; k < RUIN_AT; k++) {
            int px = pipX + k * 11;
            g.fill(px, footerY - 1, px + 9, footerY + 8, k < slips ? 0xFFB02018 : 0xFF8F8F8F);
            g.renderOutline(px, footerY - 1, 9, 9, 0xFF555555);
        }
        Component hint = Component.translatable("berlords_carving.screen.hint");
        g.drawString(this.font, hint, left + PANEL_W / 2 - this.font.width(hint) / 2, footerY + 15, 0xFF2E2E2E, false);
        Component cancel = Component.translatable("berlords_carving.screen.cancel");
        g.drawString(this.font, cancel, left + PANEL_W / 2 - this.font.width(cancel) / 2, footerY + 27, 0xFF8A1410, false);

        if (!hoveredTab.isEmpty()) {
            g.renderTooltip(this.font, hoveredTab, mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
