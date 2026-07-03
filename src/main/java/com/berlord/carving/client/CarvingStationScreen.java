package com.berlord.carving.client;

import com.berlord.carving.ArmorKind;
import com.berlord.carving.Carving;
import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.ToolKind;
import com.berlord.carving.block.CarvingStationBlock;
import com.berlord.carving.menu.CarvingStationMenu;
import com.berlord.carving.net.StationCarveResultPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static com.berlord.carving.client.ShapeLibrary.CELLS;
import static com.berlord.carving.client.ShapeLibrary.GRID;
import static com.berlord.carving.menu.CarvingStationMenu.CELL;
import static com.berlord.carving.menu.CarvingStationMenu.GRID_PX;
import static com.berlord.carving.menu.CarvingStationMenu.GRID_X;
import static com.berlord.carving.menu.CarvingStationMenu.GRID_Y;
import static com.berlord.carving.menu.CarvingStationMenu.INPUT_X;
import static com.berlord.carving.menu.CarvingStationMenu.INPUT_Y;
import static com.berlord.carving.menu.CarvingStationMenu.INV_X;
import static com.berlord.carving.menu.CarvingStationMenu.INV_Y;
import static com.berlord.carving.menu.CarvingStationMenu.OUTPUT_X;
import static com.berlord.carving.menu.CarvingStationMenu.OUTPUT_Y;
import static com.berlord.carving.menu.CarvingStationMenu.TABS_Y;
import static com.berlord.carving.menu.CarvingStationMenu.TAB_H;
import static com.berlord.carving.menu.CarvingStationMenu.TAB_W;

/**
 * Tier-2 carving station: a water-jet cutter. The jet sits at the canvas centre; W/S drive it
 * (screen-fixed), A/D rotate the canvas, SPACE toggles it (only while the station is waterlogged).
 * While on it draws a free-form cut line. A self-loop, or a cut that runs from the black back into the
 * black, severs a piece: a present cell falls when the straight segment from its centre to the shape's
 * single anchor (the keep-centroid; disjoint shapes such as two boots are bridged into one silhouette
 * first) crosses the cut an ODD number of times -- i.e. the cut separates it from the shape. This
 * handles edge-to-edge slices, corner nicks and enclosing loops alike. Grazing or cutting off a good
 * cell is an error: 1 free, 2 = -30% durability, 3 = break.
 */
public class CarvingStationScreen extends AbstractContainerScreen<CarvingStationMenu> {
    private static final int BREAK_AT = 3;            // 1 free, 2 = -30% durability, 3 = break
    private static final double MOVE_SPEED = 5.0;     // jet travel, cells per second
    private static final double ROT_SPEED = 3.0;      // canvas rotation, rad/s (A/D)
    private static final int CONNECTOR_DROP = 2;      // boots bridge: cells the connector is pushed down
    private static final double JET_MARGIN = 1.5;     // how far off-grid the jet may travel
    private static final double MIN_SEG = 0.06;       // min jet travel before a new path point is logged
    private static final int MOVE_SIGN = -1;          // W/S direction (kept as the in-game build had it)
    private static final double[] BREAK = {Double.NaN, Double.NaN}; // stroke separator in committedPath

    private final boolean[] keep = new boolean[CELLS];
    private final boolean[] present = new boolean[CELLS];
    private final boolean[] mistake = new boolean[CELLS];     // good cells touched or cut off = errors
    private final List<double[]> path = new ArrayList<>();    // current free-form cut stroke (live)
    private final List<double[]> committedPath = new ArrayList<>(); // past strokes, kept drawn (persist on toggle)
    private int anchor = -1;                                  // single centre of interest (keep centroid)

    private CarvingMaterial mat;
    private boolean armor;
    private int selected = 0;
    private boolean built = false;
    private boolean committed = false;
    private boolean finished = false;
    private boolean jetOn = false;
    private boolean tracing = false;
    private int errors = 0;
    private int builtForCount = -1;

    private double theta = 0.0;
    private double jetX = 8.0, jetY = 8.0;
    private boolean kW, kA, kS, kD;
    private long lastMoveMs = 0;

    private TextureAtlasSprite blockSprite;

    public CarvingStationScreen(CarvingStationMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = CarvingStationMenu.IMG_W;
        this.imageHeight = CarvingStationMenu.IMG_H;
    }

    @Override
    protected void init() {
        super.init();
        lastMoveMs = Util.getMillis();
        refreshSprite();
    }

    // ---- kinds / slate ---------------------------------------------------

    private int kindCount() {
        return armor ? ArmorKind.values().length : ToolKind.values().length;
    }

    private ItemStack kindIcon(int k) {
        return Carving.iconStack(mat == null ? CarvingMaterial.IRON : mat, armor, k);
    }

    private boolean stationReady() {
        return menu.be != null;
    }

    private ItemStack inputStack() {
        return stationReady() ? menu.getSlot(0).getItem() : ItemStack.EMPTY;
    }

    private boolean hasSlate() {
        return inputStack().getItem() instanceof com.berlord.carving.item.SlateItem si && si.material.isStationOnly();
    }

    private boolean outputEmpty() {
        return stationReady() && menu.getSlot(1).getItem().isEmpty();
    }

    private boolean active() {
        return hasSlate() && outputEmpty() && !finished;
    }

    /** The water-jet only runs when the station block is waterlogged. */
    private boolean hasWater() {
        if (menu.be == null || menu.be.getLevel() == null) {
            return false;
        }
        var st = menu.be.getLevel().getBlockState(menu.be.getBlockPos());
        return st.getBlock() instanceof CarvingStationBlock && st.getValue(CarvingStationBlock.WATERLOGGED);
    }

    private int inputCount() {
        return hasSlate() ? inputStack().getCount() : 0;
    }

    private void refreshSprite() {
        ResourceLocation tex = ResourceLocation.parse(bgTexture(mat == null ? CarvingMaterial.IRON : mat));
        this.blockSprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(tex);
    }

    private static String bgTexture(CarvingMaterial m) {
        return switch (m) {
            case COPPER -> "minecraft:block/copper_block";
            case IRON -> "minecraft:block/iron_block";
            case GOLDEN -> "minecraft:block/gold_block";
            case DIAMOND -> "minecraft:block/diamond_block";
            case EMERALD -> "minecraft:block/emerald_block";
            case AMETHYST -> "minecraft:block/amethyst_block";
            case LAPIS -> "minecraft:block/lapis_block";
            case QUARTZ -> "minecraft:block/quartz_block";
            case OBSIDIAN -> "minecraft:block/obsidian";
            case ECHO -> "minecraft:block/sculk";
            case DEEP_ALLOY -> "slag:block/deep_alloy_block";
            case ROSE_GOLD -> "slag:block/rose_gold_block";
            default -> "minecraft:block/stone";
        };
    }

    private void buildShape() {
        int[] base = ShapeLibrary.levels(Carving.shapeKey(mat, armor, selected));
        for (int i = 0; i < CELLS; i++) {
            keep[i] = base[i] >= 0;
            present[i] = true;
            mistake[i] = false;
        }
        bridgeKeepComponents(); // join disjoint shapes (e.g. two boots) into one connected silhouette
        anchor = keepCentroidCell();
        path.clear();
        committedPath.clear();
        errors = 0;
        committed = false;
        finished = false;
        jetOn = false;
        tracing = false;
        theta = 0;
        jetX = jetY = GRID / 2.0;
        built = true;
    }

    /** 4-connected keep components (lists of cell indices). */
    private List<List<Integer>> keepComponents() {
        List<List<Integer>> comps = new ArrayList<>();
        boolean[] seen = new boolean[CELLS];
        int[] stack = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            if (!keep[i] || seen[i]) {
                continue;
            }
            List<Integer> comp = new ArrayList<>();
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            while (sp > 0) {
                int c = stack[--sp];
                comp.add(c);
                int col = c % GRID, row = c / GRID;
                if (col > 0 && keep[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1; }
                if (col < GRID - 1 && keep[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1; }
                if (row > 0 && keep[c - GRID] && !seen[c - GRID]) { seen[c - GRID] = true; stack[sp++] = c - GRID; }
                if (row < GRID - 1 && keep[c + GRID] && !seen[c + GRID]) { seen[c + GRID] = true; stack[sp++] = c + GRID; }
            }
            comps.add(comp);
        }
        return comps;
    }

    /** Connect disjoint keep regions with thin keep bridges so the silhouette is one shape (one anchor). */
    private void bridgeKeepComponents() {
        for (int guard = 0; guard < 8; guard++) {
            List<List<Integer>> comps = keepComponents();
            if (comps.size() <= 1) {
                return;
            }
            List<Integer> base = comps.get(0);
            int ba = -1, bb = -1;
            double best = Double.MAX_VALUE;
            List<Integer> other = null;
            for (int ci = 1; ci < comps.size(); ci++) {
                for (int a : base) {
                    for (int b : comps.get(ci)) {
                        double d = Math.hypot(a % GRID - b % GRID, a / GRID - b / GRID);
                        if (d < best) {
                            best = d;
                            ba = a;
                            bb = b;
                            other = comps.get(ci);
                        }
                    }
                }
            }
            // push the connector CONNECTOR_DROP cells DOWN (both with and without Slag)
            ba = nearestCellTo(base, ba % GRID, Math.max(0, Math.min(GRID - 1, ba / GRID + CONNECTOR_DROP)));
            bb = nearestCellTo(other, bb % GRID, Math.max(0, Math.min(GRID - 1, bb / GRID + CONNECTOR_DROP)));
            // mark the cells on the straight line between the two cells as keep
            int x0 = ba % GRID, y0 = ba / GRID, x1 = bb % GRID, y1 = bb / GRID;
            int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            for (int s = 0; s <= steps; s++) {
                int x = Math.round(x0 + (x1 - x0) * (s / (float) Math.max(1, steps)));
                int y = Math.round(y0 + (y1 - y0) * (s / (float) Math.max(1, steps)));
                if (x >= 0 && x < GRID && y >= 0 && y < GRID) {
                    keep[y * GRID + x] = true;
                }
            }
        }
    }

    /** The cell in {@code comp} closest to (tx,ty). */
    private int nearestCellTo(List<Integer> comp, int tx, int ty) {
        int best = comp.get(0);
        double bd = Double.MAX_VALUE;
        for (int c : comp) {
            double d = Math.hypot(c % GRID - tx, c / GRID - ty);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    /** The keep cell nearest the keep centroid -- the single anchor. */
    private int keepCentroidCell() {
        long sx = 0, sy = 0, n = 0;
        for (int i = 0; i < CELLS; i++) {
            if (keep[i]) {
                sx += i % GRID;
                sy += i / GRID;
                n++;
            }
        }
        if (n == 0) {
            return -1;
        }
        double cx = (double) sx / n, cy = (double) sy / n;
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < CELLS; i++) {
            if (keep[i]) {
                double d = Math.hypot(i % GRID - cx, i / GRID - cy);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
        }
        return best;
    }

    // ---- tick / cutting --------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!stationReady()) {
            return;
        }
        int count = inputCount();
        if (count == 0) {
            built = false;
            committed = false;
            finished = false;
            jetOn = false;
            tracing = false;
            return;
        }
        if (!outputEmpty()) {
            return;
        }
        if (inputStack().getItem() instanceof com.berlord.carving.item.SlateItem si) {
            if (si.material != mat || si.big != armor) {
                mat = si.material;
                armor = si.big;
                selected = Math.min(selected, kindCount() - 1);
                refreshSprite();
                built = false;
            }
        }
        if (!built || count != builtForCount) {
            buildShape();
            builtForCount = count;
        }
        // Water gate: the jet needs the station waterlogged. If the water is removed mid-operation
        // (jet running or a cut already started), the jet stops and the worked slate is ruined
        // (finish(false) -> server destroys the input, no output).
        if ((jetOn || committed) && !hasWater()) {
            jetOn = false;
            finish(false);
            return;
        }
        boolean now = active() && jetOn;
        if (now && !tracing) {            // pen down: start a fresh live stroke
            path.clear();
            path.add(new double[]{jetX, jetY});
        } else if (!now && tracing) {      // pen up
            // Persist only an OPEN, in-progress cut -- one paused with the jet still over present
            // material. A stroke that ends in the void is a finished (or empty) gesture, so its line is
            // discarded: anything it severed already shows as holes, and a void->void cut that severed
            // nothing is a no-op that must leave no lingering line.
            double[] tip = path.isEmpty() ? null : path.get(path.size() - 1);
            if (tip != null && !voidAt(tip[0], tip[1])) {
                commitLine(path);
            }
            path.clear();
        }
        if (now) {
            extendPath();
        }
        tracing = now;
    }

    /** Append a finished stroke to the persistent drawn lines (separated by a BREAK), bounding the list. */
    private void commitLine(List<double[]> pts) {
        if (pts.size() < 2) {
            return; // need at least a segment to draw anything
        }
        committedPath.addAll(pts);
        committedPath.add(BREAK);
        if (committedPath.size() > 4000) {
            committedPath.subList(0, committedPath.size() - 2000).clear();
        }
    }

    private void extendPath() {
        if (path.isEmpty()) {
            path.add(new double[]{jetX, jetY});
            return;
        }
        double[] last = path.get(path.size() - 1);
        if (Math.hypot(jetX - last[0], jetY - last[1]) < MIN_SEG) {
            return;
        }
        markTouched(last[0], last[1], jetX, jetY); // any good cell the jet grazes is a mistake
        if (finished) {
            return;
        }
        // 1) self-crossing (earliest) = a closed loop
        int hit = -1;
        double[] x = null;
        for (int i = 0; i < path.size() - 2 && hit < 0; i++) {
            double[] a = path.get(i), b = path.get(i + 1);
            double[] p = segIntersect(last[0], last[1], jetX, jetY, a[0], a[1], b[0], b[1]);
            if (p != null) {
                hit = i;
                x = p;
            }
        }
        path.add(new double[]{jetX, jetY});
        if (hit >= 0) {
            resolveLoop(hit, x);
            return;
        }
        // 2) a cut that ends in the void -> the chord closes through the black -> cut-off piece
        if (voidAt(jetX, jetY) && !voidAt(last[0], last[1])) {
            int e = -1;
            for (int i = path.size() - 3; i >= 0; i--) {
                if (voidAt(path.get(i)[0], path.get(i)[1])) {
                    e = i;
                    break;
                }
            }
            // all black/void is one cut area: a cut reaching black closes from the stroke start if it
            // never passed through the void earlier
            resolveVoidCut(e < 0 ? 0 : e);
        }
        if (path.size() > 4000) {
            path.subList(0, path.size() - 2000).clear();
        }
    }

    private boolean voidAt(double px, double py) {
        int c = (int) Math.floor(px), r = (int) Math.floor(py);
        return c < 0 || c >= GRID || r < 0 || r >= GRID || !present[r * GRID + c];
    }

    /** Any good (keep) cell the jet path passes through is a mistake -- even a slight touch. */
    private void markTouched(double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.2));
        for (int s = 0; s <= steps; s++) {
            double tt = (double) s / steps;
            int col = (int) Math.floor(x0 + dx * tt), row = (int) Math.floor(y0 + dy * tt);
            if (col >= 0 && col < GRID && row >= 0 && row < GRID) {
                int c = row * GRID + col;
                if (keep[c] && !mistake[c]) {
                    mistake[c] = true;
                    committed = true;
                }
            }
        }
        int e = countErrors();
        if (e > errors) {
            errors = e;
            playClick(0.5F);
            if (errors >= BREAK_AT) {
                finish(false);
            }
        }
    }

    /** A self-closed loop: sever the enclosed region; keep the lead-in tail DRAWN but out of the live path. */
    private void resolveLoop(int hitI, double[] x) {
        List<double[]> loop = new ArrayList<>();
        loop.add(x);
        for (int i = hitI + 1; i < path.size(); i++) {
            loop.add(path.get(i));
        }
        loop.add(x); // close the loop so the crossing parity is clean
        resolve(loop);
        // Keep the lead-in tail VISIBLE (commit it) so finishing a circle doesn't wipe your in-progress
        // cut -- but take it OUT of the live path. A retained tail would re-trip self-loop detection on a
        // later re-cross AND fold its stale geometry into a following void-cut. The live stroke restarts
        // from the crossing point x.
        if (hitI >= 1) {
            List<double[]> tail = new ArrayList<>(path.subList(0, hitI + 1));
            tail.add(new double[]{x[0], x[1]});
            commitLine(tail);
        }
        path.clear();
        path.add(x);
    }

    /** A void->material->void cut (edge-to-edge slice or corner nick): sever the piece, discard the line. */
    private void resolveVoidCut(int e) {
        List<double[]> poly = new ArrayList<>(path.subList(e, path.size()));
        resolve(poly);
        // A void->material->void cut is a finished gesture closed through the black. Whatever it severed
        // is already gone (shown as holes); if it severed nothing it was a no-op -- like a tiny circle.
        // Either way leave NO lingering line: discard the whole traced arc AND its lead-in, restarting
        // from the void so the stroke can't later self-close into a stray loop or invalidate a good cut.
        double[] mark = path.get(e);
        path.clear();
        path.add(mark);
    }

    /**
     * Sever every present cell the cut separates from the shape: a cell falls when the straight segment
     * from its centre to the anchor centre crosses the cut an ODD number of times. Geometry-robust for
     * enclosing loops, edge-to-edge slices and corner nicks alike (no polygon closure to go degenerate).
     */
    private void resolve(List<double[]> cut) {
        if (anchor < 0 || cut.size() < 2) {
            return;
        }
        // nudge the anchor sample off the exact cell centre so a cut can't land precisely on the shared
        // ray endpoint (which would flip every cell's parity at once) -- a generic point avoids that.
        double ax = anchor % GRID + 0.513, ay = anchor / GRID + 0.529;
        boolean fell = false;
        for (int c = 0; c < CELLS; c++) {
            if (!present[c] || c == anchor) {
                continue;
            }
            double px = c % GRID + 0.5, py = c / GRID + 0.5;
            int cross = 0;
            for (int i = 0; i + 1 < cut.size(); i++) {
                double[] a = cut.get(i), b = cut.get(i + 1);
                if (Double.isNaN(a[0]) || Double.isNaN(b[0])) {
                    continue;
                }
                if (segIntersect(px, py, ax, ay, a[0], a[1], b[0], b[1]) != null) {
                    cross++;
                }
            }
            if ((cross & 1) == 1) { // separated from the shape by the cut
                present[c] = false;
                if (keep[c]) {
                    mistake[c] = true; // a good cell that got cut off is a mistake
                }
                fell = true;
            }
        }
        if (!fell) {
            return;
        }
        committed = true;
        errors = countErrors();
        playClick(0.9F);
        if (errors >= BREAK_AT) {
            finish(false);
        } else if (isComplete()) {
            finish(true);
        }
    }

    private static double[] segIntersect(double ax, double ay, double bx, double by,
                                         double cx, double cy, double dx, double dy) {
        double rx = bx - ax, ry = by - ay, sx = dx - cx, sy = dy - cy;
        double denom = rx * sy - ry * sx;
        if (Math.abs(denom) < 1e-9) {
            return null;
        }
        double t = ((cx - ax) * sy - (cy - ay) * sx) / denom;
        double u = ((cx - ax) * ry - (cy - ay) * rx) / denom;
        if (t >= 0 && t <= 1 && u >= 0 && u <= 1) {
            return new double[]{ax + t * rx, ay + t * ry};
        }
        return null;
    }

    private int countErrors() {
        int m = 0;
        for (int i = 0; i < CELLS; i++) {
            if (keep[i] && mistake[i]) { // good cells touched or cut off
                m++;
            }
        }
        return m;
    }

    private boolean isComplete() {
        for (int i = 0; i < CELLS; i++) {
            if (!keep[i] && present[i]) {
                return false;
            }
        }
        return true;
    }

    private void finish(boolean success) {
        if (finished) {
            return;
        }
        finished = true;
        jetOn = false;
        tracing = false;
        if (menu.be != null && mat != null) {
            PacketDistributor.sendToServer(new StationCarveResultPayload(
                    menu.be.getBlockPos(), mat.ordinal(), armor, selected, errors, success));
        }
    }

    private void playClick(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.GRINDSTONE_USE, pitch, 0.2F));
    }

    @Override
    public void onClose() {
        if (committed && !finished && hasSlate()) {
            finish(false);
        }
        super.onClose();
    }

    // ---- input -----------------------------------------------------------

    private int tabsLeft() {
        return leftPos + (imageWidth - kindCount() * TAB_W) / 2;
    }

    private int tabX(int k) {
        return tabsLeft() + k * TAB_W;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (hasSlate() && !committed && stationReady()) {
            int ty = topPos + TABS_Y;
            for (int k = 0; k < kindCount(); k++) {
                int tx = tabX(k);
                if (mx >= tx && mx < tx + TAB_W && my >= ty && my < ty + TAB_H) {
                    if (k != selected) {
                        selected = k;
                        built = false;
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            if (jetOn) {
                jetOn = false;                          // stopping the jet is always allowed
            } else if (active() && hasWater()) {
                jetOn = true;                           // the jet only starts when the station has water
            } else if (active()) {
                playClick(0.4F);                        // denied: no water in the station
            }
            return true;
        }
        if (setKey(key, true)) {
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (setKey(key, false)) {
            return true;
        }
        return super.keyReleased(key, scan, mods);
    }

    private boolean setKey(int key, boolean down) {
        switch (key) {
            case GLFW.GLFW_KEY_W -> kW = down;
            case GLFW.GLFW_KEY_A -> kA = down;
            case GLFW.GLFW_KEY_S -> kS = down;
            case GLFW.GLFW_KEY_D -> kD = down;
            default -> {
                return false;
            }
        }
        return true;
    }

    /** W/S move the jet (screen-fixed vertical, regardless of rotation); A/D rotate the canvas. */
    private void advanceJet() {
        long now = Util.getMillis();
        double dt = Math.min(0.1, Math.max(0, (now - lastMoveMs) / 1000.0));
        lastMoveMs = now;
        if (!active()) {
            return;
        }
        double rot = (kA ? 1 : 0) - (kD ? 1 : 0); // A and D rotate the canvas opposite ways
        if (rot != 0) {
            theta += rot * ROT_SPEED * dt;
        }
        double mvy = (kS ? 1 : 0) - (kW ? 1 : 0);
        if (mvy != 0) {
            double v = MOVE_SPEED * dt * MOVE_SIGN;
            double uy = -mvy;
            double cos = Math.cos(theta), sin = Math.sin(theta);
            jetX += uy * sin * v;
            jetY += uy * cos * v;
            jetX = Math.max(-JET_MARGIN, Math.min(GRID + JET_MARGIN, jetX));
            jetY = Math.max(-JET_MARGIN, Math.min(GRID + JET_MARGIN, jetY));
        }
    }

    // ---- render ----------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        advanceJet();
        super.render(g, mouseX, mouseY, partialTick);
        if (hasSlate()) {
            renderTabs(g, mouseX, mouseY);
            renderJet(g);
            renderErrorPips(g);
            renderControls(g);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        drawPanel(g, leftPos, topPos, imageWidth, imageHeight);
        drawSlot(g, leftPos + INPUT_X, topPos + INPUT_Y);
        drawSlot(g, leftPos + OUTPUT_X, topPos + OUTPUT_Y);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlot(g, leftPos + INV_X + col * 18, topPos + INV_Y + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlot(g, leftPos + INV_X + col * 18, topPos + CarvingStationMenu.HOTBAR_Y);
        }
        renderCanvas(g);
    }

    private void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        int x1 = x + w, y1 = y + h;
        g.fill(x - 1, y - 1, x1 + 1, y1 + 1, 0xFF000000);
        g.fill(x, y, x1, y1, 0xFFC6C6C6);
        g.fill(x, y, x1, y + 2, 0xFFFFFFFF);
        g.fill(x, y, x + 2, y1, 0xFFFFFFFF);
        g.fill(x, y1 - 2, x1, y1, 0xFF555555);
        g.fill(x1 - 2, y, x1, y1, 0xFF555555);
    }

    private void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
        g.fill(x - 1, y - 1, x + 16, y, 0xFF373737);
        g.fill(x - 1, y - 1, x, y + 16, 0xFF373737);
        g.fill(x, y + 16, x + 17, y + 17, 0xFFFFFFFF);
        g.fill(x + 16, y, x + 17, y + 17, 0xFFFFFFFF);
    }

    private void renderCanvas(GuiGraphics g) {
        int gx = leftPos + GRID_X, gy = topPos + GRID_Y;
        g.fill(gx - 2, gy - 2, gx + GRID_PX, gy + GRID_PX, 0xFF373737);
        g.fill(gx, gy, gx + GRID_PX + 2, gy + GRID_PX + 2, 0xFFFFFFFF);

        if (!stationReady() || !hasSlate()) {
            g.fill(gx, gy, gx + GRID_PX, gy + GRID_PX, 0xFF8B8B8B);
            Component msg = Component.translatable("berlords_carving.station.insert");
            g.drawString(this.font, msg, gx + GRID_PX / 2 - this.font.width(msg) / 2,
                    gy + GRID_PX / 2 - 4, 0xFF3A3A3A, false);
            return;
        }

        g.fill(gx, gy, gx + GRID_PX, gy + GRID_PX, 0xFF0E0E0E);
        double cx = gx + GRID_PX / 2.0, cy = gy + GRID_PX / 2.0;
        g.enableScissor(gx, gy, gx + GRID_PX, gy + GRID_PX);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) theta));
        g.pose().translate((float) -(gx + jetX * CELL), (float) -(gy + jetY * CELL), 0);
        for (int i = 0; i < CELLS; i++) {
            if (!present[i]) {
                continue;
            }
            int x = gx + (i % GRID) * CELL, y = gy + (i / GRID) * CELL;
            g.blit(x, y, 0, CELL, CELL, blockSprite);
            if (!keep[i]) {
                g.fill(x, y, x + CELL, y + CELL, 0x55000000); // waste: only slightly dimmer than true colour
            }
        }
        g.pose().popPose();
        renderPath(g);
        g.disableScissor();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    private double[] toScreen(double px, double py) {
        double cx = leftPos + GRID_X + GRID_PX / 2.0, cy = topPos + GRID_Y + GRID_PX / 2.0;
        double dx = (px - jetX) * CELL, dy = (py - jetY) * CELL;
        double cos = Math.cos(theta), sin = Math.sin(theta);
        return new double[]{cx + dx * cos - dy * sin, cy + dx * sin + dy * cos};
    }

    /** Draw the free-form cut line, but ONLY where it is over present material (no lines over holes). */
    private void renderPath(GuiGraphics g) {
        drawStrokes(g, committedPath); // past strokes (persist across jet toggle)
        drawStrokes(g, path);          // the live stroke
    }

    /** Draw a stroke list as a red line clipped to present material; BREAK points separate sub-strokes. */
    private void drawStrokes(GuiGraphics g, List<double[]> pts) {
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            if (Double.isNaN(a[0]) || Double.isNaN(b[0])) {
                continue; // don't connect across a stroke break
            }
            double dx = b[0] - a[0], dy = b[1] - a[1];
            int steps = (int) Math.max(1, Math.ceil(Math.hypot(dx, dy) / 0.08));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                double px = a[0] + dx * t, py = a[1] + dy * t;
                int col = (int) Math.floor(px), row = (int) Math.floor(py);
                if (col < 0 || col >= GRID || row < 0 || row >= GRID || !present[row * GRID + col]) {
                    continue; // no line over holes / empty space
                }
                double[] sc = toScreen(px, py);
                int sx = (int) Math.round(sc[0]), sy = (int) Math.round(sc[1]);
                g.fill(sx, sy, sx + 1, sy + 1, 0xFFFF3322);
            }
        }
    }

    private void renderJet(GuiGraphics g) {
        int jx = leftPos + GRID_X + GRID_PX / 2, jy = topPos + GRID_Y + GRID_PX / 2;
        if (jetOn) {
            g.fill(jx - 3, jy - 3, jx + 3, jy + 3, 0x55FF3020);
            g.fill(jx, jy - 5, jx + 1, jy + 5, 0x66FF4030);
            g.fill(jx - 5, jy, jx + 5, jy + 1, 0x66FF4030);
            g.fill(jx - 1, jy - 1, jx + 1, jy + 1, 0xFFFF2010);
        } else {
            g.fill(jx - 1, jy - 1, jx + 1, jy + 1, 0xFF802018);
        }
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        ItemStack hovered = ItemStack.EMPTY;
        int ty = topPos + TABS_Y;
        for (int k = 0; k < kindCount(); k++) {
            int tx = tabX(k);
            boolean sel = k == selected;
            boolean hov = mouseX >= tx && mouseX < tx + TAB_W && mouseY >= ty && mouseY < ty + TAB_H;
            g.fill(tx, ty, tx + TAB_W - 1, ty + TAB_H, sel ? 0xFFAFAFAF : (hov ? 0xFFBDBDBD : 0xFF8B8B8B));
            g.renderOutline(tx, ty, TAB_W - 1, TAB_H, sel ? 0xFF303030 : 0xFF6E6E6E);
            ItemStack icon = kindIcon(k);
            g.renderItem(icon, tx + (TAB_W - 16) / 2, ty + (TAB_H - 16) / 2);
            if (committed && !sel) {
                g.fill(tx, ty, tx + TAB_W - 1, ty + TAB_H, 0x80AAAAAA);
            }
            if (hov) {
                hovered = icon;
            }
        }
        if (!hovered.isEmpty()) {
            g.renderTooltip(this.font, hovered, mouseX, mouseY);
        }
    }

    private void renderErrorPips(GuiGraphics g) {
        int px = leftPos + GRID_X + GRID_PX + 6;
        int py = topPos + GRID_Y;
        int pip = 9, gap = 4;
        for (int k = 0; k < BREAK_AT; k++) {
            int y = py + k * (pip + gap);
            int col = k < errors
                    ? (k == 0 ? 0xFFE0A020 : (k == BREAK_AT - 1 ? 0xFF8E1410 : 0xFFB02018))
                    : 0xFF8F8F8F;
            g.fill(px, y, px + pip, y + pip, col);
            g.renderOutline(px, y, pip, pip, 0xFF555555);
        }
    }

    private void renderControls(GuiGraphics g) {
        Component c = Component.translatable("berlords_carving.station.controls");
        g.drawString(this.font, c, leftPos + (imageWidth - this.font.width(c)) / 2,
                topPos + GRID_Y + GRID_PX + 4, 0xFF3A3A3A, false);
        if (active() && !hasWater()) {
            Component w = Component.translatable("berlords_carving.station.needs_water");
            g.drawString(this.font, w, leftPos + (imageWidth - this.font.width(w)) / 2,
                    topPos + GRID_Y + GRID_PX + 14, 0xFFB02018, false);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, (imageWidth - this.font.width(this.title)) / 2, 6, 0xFF222222, false);
    }
}
