package com.strengthlaw;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import net.imagej.mesh.Mesh;
import net.imagej.ops.OpService;
import net.imagej.ops.Ops;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Cursor;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;
import net.imglib2.converter.Converters;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.widget.Button;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;  
import java.awt.event.WindowEvent;  
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.PolygonRoi;
import ij.gui.ShapeRoi;
import ij.gui.RoiListener;
import ij.process.FloatPolygon;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;

import ij.process.ImageProcessor;
import ij.process.ByteProcessor;
import ij.process.BinaryProcessor;
import ij.process.AutoThresholder;

@Plugin(type = Command.class, menuPath = "Plugins>StrengthLawExtractor>StrengthLawExtractor")
public class StrengthLawExtractor implements Command {

    @Parameter private UIService ui;
    @Parameter private OpService ops;

    @Parameter(label = "Pores appear as", choices = {"black","white"})
    private String poresAre = "black";

    @Parameter(label = "Fractional tolerance (0–1)", min="0.0", max="1.0")
    private double fracTol = 0.0;

    @Parameter(label = "Voxel size Z")
    private double vz = 1.0;
    @Parameter(label = "Voxel size Y")
    private double vy = 1.0;
    @Parameter(label = "Voxel size X")
    private double vx = 1.0;

    @Parameter(label = "Units label (e.g., px, µm, mm)")
    private String units = "px";

    @Parameter(label = "Use image calibration (voxel size + units)")
    private boolean useImageCalibration = true;

    @Parameter(label = "Pad solid boundary")
    private boolean padSolid = false;

    @Parameter(label = "Clear border voxels (can erase specimen if touching edges)")
    private boolean clearBorderVoxels = false;

    @Parameter(label = "Export Results (.csv)", style = "save", required = false)
    private File resultsCSV;

    @Parameter(label = "Export Mesh (.stl)", style = "save", required = false)
    private File meshSTL;

    @Parameter(label = "Compute Features", style = "button", callback = "onCompute")
    private Button computeBtn;

    @Parameter(label = "Export Results + STL", style = "button", callback = "onExport")
    private Button exportBtn;

    @Parameter(label = "Preview ROI", style = "button", callback = "onPreviewVOI")
    private Button previewVoiBtn;

    @Parameter(label = "Acquire / Update User ROI", style = "button", callback = "onAcquireUserVOI")
    private Button acquireUserVoiBtn;

    @Parameter(type = ItemIO.OUTPUT)
    private ResultsTable table;

    @Parameter(
            label = "Computation mode",
            choices = {"Mesh (accurate, heavier)", "Voxel wireframe (preview, memory-light)"},
            description = "Choose mesh for full-accuracy metrics; choose voxel for lightweight 3D preview export"
    )
    private String mode = "Mesh (accurate, heavier)";

    @Parameter(
            label = "Export format",
            choices = {"None", "STL (mesh)", "OBJ wireframe (mesh)", "OBJ wireframe (voxel)"},
            description = "What to write when you press Export"
    )
    private String exportFormat = "None";

    @Parameter(
            label = "ROI mode",
            choices = {"Auto ROI", "User-drawn ROI", "User-guided Auto ROI"},
            description = "How to define specimen ROI (analysis region): auto, manual ROI, or auto refined inside your ROI"
    )
    private String voiMode = "Auto ROI";

    @Parameter(
            label = "User ROI source",
            choices = {"Current ROI (all slices)", "ROI Manager (per-slice)"},
            description = "Where to take the user ROI from"
    )
    private String userVoiSource = "Current ROI (all slices)";

    @Parameter(
            label = "Interpolate missing ROI slices (ROI Manager)",
            description = "If enabled, ROIs drawn on some slices are propagated to missing slices (nearest-neighbor) to handle irregular specimens slice-to-slice"
    )
    private boolean interpolateRoiManagerSlices = true;

    @Parameter(
            label = "Use outer shell only (0 = off)",
            min = "0",
            description = "If > 0, restrict ROI to an outer shell of this thickness (in voxels) on each slice"
    )
    private int shellThicknessVox = 0;

    @Parameter(
            label = "Auto ROI close iterations",
            min = "1",
            description = "For speckled specimens: higher merges islands into a solid specimen region"
    )
    private int autoCloseIters = 40;

    @Parameter(
            label = "Auto ROI threshold method",
            choices = {"Otsu", "Triangle", "Default", "Huang", "Intermodes", "IsoData", "Li", "MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Percentile", "RenyiEntropy", "Shanbhag", "Yen"},
            description = "Method used to segment the specimen region per slice before morphology"
    )
    private String autoVoiThreshMethod = "Otsu";

    @Parameter(
            label = "Specimen appears as",
            choices = {"bright", "dark"},
            description = "Auto ROI: choose whether the specimen is brighter or darker than background"
    )
    private String specimenAppearsAs = "bright";

    @Parameter(
            label = "Auto ROI: force circular specimen mask",
            description = "If enabled, each slice's specimen ROI is replaced by a best-fit circle from the segmented largest component (useful for CT cylindrical samples on black background)"
    )
    private boolean autoVoiForceCircle = true;

    @Parameter(
            label = "Auto ROI: background delta (0-255)",
            min = "0",
            max = "255",
            description = "If histogram thresholding fails, segment specimen as pixels differing from background mode by at least this amount (background is often black in CT)"
    )
    private int autoVoiBgDelta = 12;

    private RandomAccessibleInterval<BitType> bin01 = null;
    private Mesh mesh = null;
    private LinkedHashMap<String, Double> metrics = new LinkedHashMap<>();

    private RandomAccessibleInterval<BitType> specimenVOI = null;

    private volatile RandomAccessibleInterval<BitType> cachedUserVOI = null;
    private volatile boolean cachedUserVoiValid = false;

    private final AtomicBoolean demodalizerStarted = new AtomicBoolean(false);
    private volatile Timer demodalizeTimer = null;

    private static final class RoiThresholdResult {
        final Img<BitType> pores;
        final Img<BitType> solid;
        final long roiVoxels;
        final long poreVoxels;
        final long solidVoxels;
        RoiThresholdResult(final Img<BitType> p, final Img<BitType> s, final long rv, final long pv, final long sv) {
            this.pores = p;
            this.solid = s;
            this.roiVoxels = rv;
            this.poreVoxels = pv;
            this.solidVoxels = sv;
        }
    }

    private volatile boolean keepPluginOnTop = false;
    private volatile boolean userIsDrawingRoi = false;

    private final Object roiDrawLock = new Object();
    private volatile Timer roiIdleTimer = null;
    private final AtomicBoolean roiListenerInstalled = new AtomicBoolean(false);

    private final AtomicBoolean windowHookInstalled = new AtomicBoolean(false); 
    private volatile AWTEventListener windowHook = null; 

    private void installRoiDrawingGuard() {
        if (!roiListenerInstalled.compareAndSet(false, true)) return;
        try {
            Roi.addRoiListener(new RoiListener() {
                @Override
                public void roiModified(final ImagePlus imp, final int id) {
                    userIsDrawingRoi = true;
                    synchronized (roiDrawLock) {
                        try {
                            if (roiIdleTimer != null) roiIdleTimer.stop();
                        } catch (Throwable ignored) {}
                        roiIdleTimer = new Timer(600, e -> userIsDrawingRoi = false);
                        roiIdleTimer.setRepeats(false);
                        roiIdleTimer.start();
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private void installCommandDialogWindowHook() { 
        if (!windowHookInstalled.compareAndSet(false, true)) return;
        try {
            windowHook = new AWTEventListener() {
                @Override
                public void eventDispatched(AWTEvent event) {
                    try {
                        if (!(event instanceof WindowEvent)) return;
                        final WindowEvent we = (WindowEvent) event;
                        final int id = we.getID();
                        if (id != WindowEvent.WINDOW_OPENED && id != WindowEvent.WINDOW_ACTIVATED) return;

                        final Window w = we.getWindow();
                        if (!(w instanceof Dialog)) return;
                        final Dialog d = (Dialog) w;
                        if (!isOurDialog(d)) return;

                        try {
                            if (d.getModalityType() != Dialog.ModalityType.MODELESS) {
                                d.setModalityType(Dialog.ModalityType.MODELESS);
                            }
                        } catch (Throwable ignored) {}

                        try {
                            d.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
                        } catch (Throwable ignored) {}

                        if (d instanceof JDialog) {
                            try {
                                if (((JDialog) d).isModal()) ((JDialog) d).setModal(false);
                            } catch (Throwable ignored) {}
                            try {
                                ((JDialog) d).setAutoRequestFocus(false);
                            } catch (Throwable ignored) {}
                        } else {
                            try {
                                d.setAutoRequestFocus(false);
                            } catch (Throwable ignored) {}
                        }

                        try { d.setAlwaysOnTop(false); } catch (Throwable ignored) {}
                        try { d.setFocusableWindowState(true); } catch (Throwable ignored) {}

                    } catch (Throwable ignored) {}
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(windowHook,
                    AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
        } catch (Throwable ignored) {}
    }

    private void setPluginAlwaysOnTop(final boolean onTop) {
        keepPluginOnTop = onTop;
        try {
            if (ui == null || ui.isHeadless()) return;
            SwingUtilities.invokeLater(() -> {
                try {
                    Window[] ws = Window.getWindows();
                    if (ws == null) return;
                    for (Window w : ws) {
                        if (!(w instanceof Dialog)) continue;
                        Dialog d = (Dialog) w;
                        if (!isOurDialog(d)) continue;
                        try { d.setAlwaysOnTop(onTop); } catch (Throwable ignored) {}
                        try { d.setFocusableWindowState(true); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void nudgeOurDialogToFrontIfAllowed() {
        try {
            if (ui == null || ui.isHeadless()) return;
            if (!keepPluginOnTop) return; 
            if (userIsDrawingRoi) return;
            SwingUtilities.invokeLater(() -> {
                try {
                    Window[] ws = Window.getWindows();
                    if (ws == null) return;
                    for (Window w : ws) {
                        if (!(w instanceof Dialog)) continue;
                        Dialog d = (Dialog) w;
                        if (!isOurDialog(d)) continue;
                        try {
                            if (!d.isVisible()) d.setVisible(true);
                            d.toFront();
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    @Override
    public void run() {
        installRoiDrawingGuard();
        installCommandDialogWindowHook();  
        scheduleDemodalizeCommandDialogs();
    }

    private long countTrue(final RandomAccessibleInterval<BitType> bin) {
        long c = 0;
        for (BitType v : Views.iterable(bin)) if (v.get()) c++;
        return c;
    }

    private void logMaskStats(final String tag, final RandomAccessibleInterval<BitType> mask) {
        if (mask == null) {
            IJ.log("[StrengthLawExtractor DEBUG] " + tag + " = null");
            return;
        }
        final long[] dims = new long[mask.numDimensions()];
        mask.dimensions(dims);

        final long[] mn = new long[mask.numDimensions()];
        final long[] mx = new long[mask.numDimensions()];
        for (int d = 0; d < mask.numDimensions(); d++) {
            mn[d] = mask.min(d);
            mx[d] = mask.max(d);
        }

        long on = countTrue(mask);

        IJ.log("[StrengthLawExtractor DEBUG] " + tag +
                " dims=(" + dims[0] + "," + dims[1] + "," + dims[2] + ")" +
                " min=(" + mn[0] + "," + mn[1] + "," + mn[2] + ")" +
                " max=(" + mx[0] + "," + mx[1] + "," + mx[2] + ")" +
                " true=" + on);
    }

    private boolean ensureNonEmpty(final String what, final RandomAccessibleInterval<BitType> mask) {
        if (mask == null) {
            IJ.log("[StrengthLawExtractor DEBUG] FAIL: " + what + " is null.");
            SwingUtilities.invokeLater(() -> IJ.error(what + " mask is null"));
            return false;
        }
        long on = countTrue(mask);
        if (on <= 0) {
            IJ.log("[StrengthLawExtractor DEBUG] FAIL: " + what + " is empty. Aborting.");
            SwingUtilities.invokeLater(() -> IJ.error(what + " mask is empty"));
            return false;
        }
        return true;
    }

    private boolean ensureNonEmptyRoiCount(final String what, final long roiCount) {
        if (roiCount <= 0) {
            IJ.log("[StrengthLawExtractor DEBUG] FAIL: " + what + " is empty (ROI contains 0 pixels). Aborting.");
            SwingUtilities.invokeLater(() -> IJ.error(what + " is empty (ROI contains 0 pixels)"));
            return false;
        }
        return true;
    }

    private RoiManager getOrCreateRoiManagerVisible() {
        RoiManager rm = RoiManager.getInstance2();
        if (rm == null) rm = new RoiManager();
        try { rm.setVisible(true); } catch (Throwable ignored) {}
        return rm;
    }

    private void bringOurDialogsToFront() {
        nudgeOurDialogToFrontIfAllowed();
    }

    public void onAcquireUserVOI() {
        scheduleDemodalizeCommandDialogs();
        Thread worker = new Thread(() -> {
            try {
                scheduleDemodalizeCommandDialogs();

                ImagePlus imp = IJ.getImage();
                if (imp == null || imp.getNSlices() < 2) {
                    SwingUtilities.invokeLater(() -> IJ.error("Open a 3D stack."));
                    return;
                }

                RandomAccessibleInterval<BitType> user = buildUserVOI(imp);
                if (user == null) {
                    cachedUserVOI = null;
                    cachedUserVoiValid = false;
                    SwingUtilities.invokeLater(() -> IJ.error("No user ROI found."));
                    return;
                }

                cachedUserVOI = user;
                cachedUserVoiValid = true;

                ImagePlus maskImp = bitMaskToBinaryByteImp(user, "User ROI Mask (binary)");
                ImagePlus maskedOrig = maskedOriginalPreview(imp, user, "User ROI Masked Original (inside kept)");

                ImagePlus ovImp = imp.duplicate();
                ovImp.setTitle("User ROI Boundary Overlay (red)");

                Overlay ov = makeBoundaryOverlayFromBinaryMask(maskImp);

                SwingUtilities.invokeLater(() -> {
                    maskImp.show();
                    maskImp.setDisplayRange(0, 255);
                    maskImp.updateAndDraw();
                    IJ.run(maskImp, "Grays", "");

                    maskedOrig.show();
                    maskedOrig.updateAndDraw();

                    ovImp.setOverlay(ov);
                    ovImp.show();
                    ovImp.updateAndDraw();
                });

            } catch (Throwable t) {
                IJ.handleException(t);
            } finally {
                bringOurDialogsToFront();
            }
        }, "StrengthLawExtractor-AcquireUserROI");

        worker.setDaemon(true);
        worker.start();
    }

    public void onPreviewVOI() {
        scheduleDemodalizeCommandDialogs();
        final boolean headless = (ui == null) || ui.isHeadless();
        final ProgressWindow pw = headless ? null : new ProgressWindow("StrengthLawExtractor: Preview ROI");

        Thread worker = new Thread(() -> {
            try {
                scheduleDemodalizeCommandDialogs();

                ImagePlus imp = IJ.getImage();
                if (imp == null || imp.getNSlices() < 2) {
                    if (pw != null) pw.close();
                    SwingUtilities.invokeLater(() -> IJ.error("Open a 3D stack."));
                    return;
                }

                if (pw != null) pw.set(5, "Preparing");
                @SuppressWarnings("unchecked")
                Img<RealType<?>> img = (Img<RealType<?>>) ImageJFunctions.wrapReal(imp);

                if (pw != null) pw.set(20, "Building ROI");
                RandomAccessibleInterval<BitType> specimenMask = buildSpecimenVOI(imp, img);
                if (specimenMask == null) {
                    if (pw != null) pw.close();
                    return;
                }
                specimenVOI = specimenMask;

                if (pw != null) pw.set(30, "Checking ROI");
                logMaskStats("ROI (preview)", specimenVOI);
                if (!ensureNonEmpty("ROI", specimenVOI)) {
                    if (pw != null) pw.close();
                    return;
                }

                if (pw != null) pw.set(60, "Showing mask");
                ImagePlus maskImp = bitMaskToBinaryByteImp(specimenMask, "ROI Mask (binary)");
                ImagePlus maskedOrig = maskedOriginalPreview(imp, specimenMask, "ROI Masked Original (inside kept)");

                if (pw != null) pw.set(75, "Creating overlay");
                ImagePlus ovImp = imp.duplicate();
                ovImp.setTitle("ROI Boundary Overlay (red)");

                Overlay ov = makeBoundaryOverlayFromBinaryMask(maskImp);

                SwingUtilities.invokeLater(() -> {
                    maskImp.show();
                    maskImp.setDisplayRange(0, 255);
                    maskImp.updateAndDraw();
                    IJ.run(maskImp, "Grays", "");

                    maskedOrig.show();
                    maskedOrig.updateAndDraw();

                    ovImp.setOverlay(ov);
                    ovImp.show();
                    ovImp.updateAndDraw();

                    bringOurDialogsToFront();
                });

                if (pw != null) pw.set(100, "Done");
            } catch (Throwable t) {
                IJ.handleException(t);
            } finally {
                if (pw != null) pw.close();
                bringOurDialogsToFront();
            }
        }, "StrengthLawExtractor-PreviewROI");

        worker.setDaemon(true);
        worker.start();
    }

    private RandomAccessibleInterval<BitType> buildSpecimenVOI(final ImagePlus imp, final Img<? extends RealType<?>> img) {
        RandomAccessibleInterval<BitType> mask;

        if ("Auto ROI".equals(voiMode)) {
            mask = buildSpecimenMaskAutoThresholded(imp);
        }
        else if ("User-drawn ROI".equals(voiMode)) {
            mask = getOrBuildUserVOI(imp);
            if (mask == null) {
                SwingUtilities.invokeLater(() -> IJ.error("User-drawn ROI: no ROI found. Use 'Acquire / Update User ROI' first, or draw ROI and click Done."));
                return null;
            }
        }
        else {
            RandomAccessibleInterval<BitType> user = getOrBuildUserVOI(imp);
            if (user == null) {
                SwingUtilities.invokeLater(() -> IJ.error("User-guided Auto ROI requires a user ROI. Use 'Acquire / Update User ROI' first, or draw ROI and click Done."));
                return null;
            } else {
                mask = buildSpecimenMaskAutoThresholdedWithinMask(imp, user);
            }
        }

        if (mask != null && shellThicknessVox > 0) {
            mask = toOuterShellPerSlice(mask, shellThicknessVox);
        }

        return mask;
    }

    private RandomAccessibleInterval<BitType> getOrBuildUserVOI(final ImagePlus imp) {
        if (cachedUserVoiValid && cachedUserVOI != null) return cachedUserVOI;
        RandomAccessibleInterval<BitType> user = buildUserVOI(imp);
        if (user != null) {
            cachedUserVOI = user;
            cachedUserVoiValid = true;
        }
        return user;
    }

    private RandomAccessibleInterval<BitType> buildUserVOI(final ImagePlus imp) {
        scheduleDemodalizeCommandDialogs();
        ensureUserVoiAvailableModeless(imp);

        if ("ROI Manager (per-slice)".equals(userVoiSource)) {
            return buildVOIFromRoiManager(imp, interpolateRoiManagerSlices);
        } else {
            return buildVOIFromCurrentRoiAllSlices(imp);
        }
    }

    private void ensureUserVoiAvailableModeless(final ImagePlus imp) {
        if (ui == null || ui.isHeadless()) return;

        scheduleDemodalizeCommandDialogs();

        if ("ROI Manager (per-slice)".equals(userVoiSource)) {
            RoiManager rm = getOrCreateRoiManagerVisible();

            Roi[] rois = rm.getRoisAsArray();
            if (rois != null && rois.length > 0) return;

            final CountDownLatch latch = new CountDownLatch(1);
            final ModelessPrompt p = new ModelessPrompt(
                    "User ROI required",
                    "Add ROI(s) to ROI Manager (press 't' or click Add).\n" +
                            "Then click Done.\n\n" +
                            "Tip: If you prefer a single ROI for all slices, set 'User ROI source' = Current ROI (all slices).\n" +
                            "ROI is the INSIDE of the ROI.",
                    latch,
                    this
            );
            p.show();

            try {
                while (latch.getCount() > 0) {
                    Roi[] rr = rm.getRoisAsArray();
                    if (rr != null && rr.length > 0) break;
                    latch.await(150, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            p.close();

        } else {
            Roi roi = imp.getRoi();
            if (roi != null) return;

            final CountDownLatch latch = new CountDownLatch(1);
            final ModelessPrompt p = new ModelessPrompt(
                    "User ROI required",
                    "Draw a ROI on the image.\n" +
                            "Then click Done.\n\n" +
                            "ROI is the INSIDE of the ROI.",
                    latch,
                    this
            );
            p.show();

            try {
                while (imp.getRoi() == null && latch.getCount() > 0) {
                    latch.await(150, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            p.close();
        }

        scheduleDemodalizeCommandDialogs();
    }

    private RandomAccessibleInterval<BitType> buildSpecimenMaskAutoThresholded(final ImagePlus imp) {

        final int w = imp.getWidth();
        final int h = imp.getHeight();
        final int nZ = imp.getNSlices();

        final boolean specimenBright = "bright".equalsIgnoreCase(specimenAppearsAs);
        final AutoThresholder.Method method = parseMethod(autoVoiThreshMethod);
        final AutoThresholder at = new AutoThresholder();

        ImageStack maskStack = new ImageStack(w, h);

        for (int z = 1; z <= nZ; z++) {
            ImageProcessor ip = imp.getStack().getProcessor(z).convertToByte(true);
            ByteProcessor src = (ByteProcessor) ip.duplicate();
            ByteProcessor bp = (ByteProcessor) src.duplicate();

            int[] hist = src.getHistogram();
            int thr = at.getThreshold(method, hist);

            if (thr < 0) thr = 0;
            if (thr > 255) thr = 255;
            if (specimenBright && thr >= 255) thr = 254;
            if (!specimenBright && thr <= 0) thr = 1;

            int bg = histogramMode(hist);

            applyThresholdToMask(src, bp, thr, specimenBright);

            long fg = countForeground(bp);
            long minOk = Math.max(512, (w * h) / 1200);
            long maxOk = (long) (0.98 * (double) w * (double) h);

            if (fg < minOk || fg > maxOk) {
                ByteProcessor test = (ByteProcessor) src.duplicate();
                applyThresholdToMask(src, test, thr, !specimenBright);
                long fg2 = countForeground(test);

                if (fg2 >= minOk && fg2 <= maxOk) {
                    bp = test;
                    fg = fg2;
                }
            }

            if (fg < minOk || fg > maxOk) {
                final int delta = Math.max(0, Math.min(255, autoVoiBgDelta));
                applyBackgroundDeltaToMask(src, bp, bg, delta);
            }

            close2D(bp, Math.max(1, autoCloseIters));
            fillHoles2D(bp);

            if (countForeground(bp) > 0) {
                keepLargestComponent2D(bp);
            }

            if (autoVoiForceCircle && countForeground(bp) > 0) {
                ByteProcessor circ = bestFitCircleFromForeground(bp);
                long cFg = countForeground(circ);
                long cMaxOk = (long) (0.95 * (double) w * (double) h);
                if (cFg > 0 && cFg <= cMaxOk) bp = circ;
            }

            maskStack.addSlice(bp);

            if ((z & 7) == 0) IJ.wait(1);
        }

        return stackToBits(maskStack);
    }

    private RandomAccessibleInterval<BitType> buildSpecimenMaskAutoThresholdedWithinMask(final ImagePlus imp,
                                                                                        final RandomAccessibleInterval<BitType> userMask) {

        final int w = imp.getWidth();
        final int h = imp.getHeight();
        final int nZ = imp.getNSlices();

        final boolean specimenBright0 = "bright".equalsIgnoreCase(specimenAppearsAs);
        final AutoThresholder.Method method = parseMethod(autoVoiThreshMethod);
        final AutoThresholder at = new AutoThresholder();

        ImageStack maskStack = new ImageStack(w, h);

        final net.imglib2.RandomAccess<BitType> raUser = userMask.randomAccess();

        for (int z = 1; z <= nZ; z++) {
            ImageProcessor ip = imp.getStack().getProcessor(z).convertToByte(true);
            ByteProcessor src = (ByteProcessor) ip.duplicate();
            ByteProcessor bp = (ByteProcessor) src.duplicate();

            int[] hist = src.getHistogram();
            int thr = at.getThreshold(method, hist);

            if (thr < 0) thr = 0;
            if (thr > 255) thr = 255;
            if (specimenBright0 && thr >= 255) thr = 254;
            if (!specimenBright0 && thr <= 0) thr = 1;

            int bg = histogramMode(hist);

            applyThresholdToMaskWithinUserMask(src, bp, raUser, thr, specimenBright0, z - 1);

            long fg = countForeground(bp);
            long minOk = Math.max(256, (w * h) / 2000);
            long maxOk = (long) (0.98 * (double) w * (double) h);

            if (fg < minOk || fg > maxOk) {
                ByteProcessor test = (ByteProcessor) src.duplicate();
                applyThresholdToMaskWithinUserMask(src, test, raUser, thr, !specimenBright0, z - 1);
                long fg2 = countForeground(test);
                if (fg2 >= minOk && fg2 <= maxOk) {
                    bp = test;
                    fg = fg2;
                }
            }

            if (fg < minOk || fg > maxOk) {
                final int delta = Math.max(0, Math.min(255, autoVoiBgDelta));
                applyBackgroundDeltaToMaskWithinUserMask(src, bp, raUser, bg, delta, z - 1);
            }

            close2D(bp, Math.max(1, autoCloseIters));
            fillHoles2D(bp);

            if (countForeground(bp) > 0) {
                keepLargestComponent2D(bp);
            }

            if (autoVoiForceCircle && countForeground(bp) > 0) {
                ByteProcessor circ = bestFitCircleFromForeground(bp);
                long cFg = countForeground(circ);
                long cMaxOk = (long) (0.95 * (double) w * (double) h);
                if (cFg > 0 && cFg <= cMaxOk) {
                    bp = circ;
                }
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (!getBit(raUser, x, y, z - 1)) bp.set(x, y, 0);
                    }
                }
            } else {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (!getBit(raUser, x, y, z - 1)) bp.set(x, y, 0);
                    }
                }
            }

            maskStack.addSlice(bp);

            if ((z & 7) == 0) IJ.wait(1);
        }

        return stackToBits(maskStack);
    }

    private AutoThresholder.Method parseMethod(String name) {
        try {
            return AutoThresholder.Method.valueOf(name);
        } catch (Throwable t) {
            return AutoThresholder.Method.Otsu;
        }
    }

    private int histogramMode(final int[] hist) {
        int best = 0;
        int bestCount = -1;
        for (int i = 0; i < hist.length; i++) {
            int c = hist[i];
            if (c > bestCount) { bestCount = c; best = i; }
        }
        return best;
    }

    private long countForeground(final ByteProcessor bp) {
        final byte[] pix = (byte[]) bp.getPixels();
        long c = 0;
        for (int i = 0; i < pix.length; i++) if ((pix[i] & 0xff) > 0) c++;
        return c;
    }

    private void applyThresholdToMask(final ByteProcessor src, final ByteProcessor dst, final int thr, final boolean specimenBright) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = src.get(x, y) & 0xff;
                boolean isSpecimen = specimenBright ? (v > thr) : (v <= thr);
                dst.set(x, y, isSpecimen ? 255 : 0);
            }
        }
    }

    private void applyBackgroundDeltaToMask(final ByteProcessor src, final ByteProcessor dst, final int bg, final int delta) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = src.get(x, y) & 0xff;
                dst.set(x, y, (Math.abs(v - bg) >= delta) ? 255 : 0);
            }
        }
    }

    private void applyThresholdToMaskWithinUserMask(final ByteProcessor src, final ByteProcessor dst,
                                                   final net.imglib2.RandomAccess<BitType> raUser,
                                                   final int thr, final boolean specimenBright, final int z0) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!getBit(raUser, x, y, z0)) {
                    dst.set(x, y, 0);
                } else {
                    int v = src.get(x, y) & 0xff;
                    boolean isSpecimen = specimenBright ? (v > thr) : (v <= thr);
                    dst.set(x, y, isSpecimen ? 255 : 0);
                }
            }
        }
    }

    private void applyBackgroundDeltaToMaskWithinUserMask(final ByteProcessor src, final ByteProcessor dst,
                                                         final net.imglib2.RandomAccess<BitType> raUser,
                                                         final int bg, final int delta, final int z0) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!getBit(raUser, x, y, z0)) {
                    dst.set(x, y, 0);
                } else {
                    int v = src.get(x, y) & 0xff;
                    dst.set(x, y, (Math.abs(v - bg) >= delta) ? 255 : 0);
                }
            }
        }
    }

    private ByteProcessor bestFitCircleFromForeground(final ByteProcessor bp) {
        final int w = bp.getWidth();
        final int h = bp.getHeight();
        final byte[] pix = (byte[]) bp.getPixels();

        long n = 0;
        double sx = 0, sy = 0;

        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) {
                if ((pix[off + x] & 0xff) > 0) {
                    n++;
                    sx += x;
                    sy += y;
                }
            }
        }

        if (n <= 0) return (ByteProcessor) bp.duplicate();

        final double cx = sx / n;
        final double cy = sy / n;

        double r2 = 0.0;
        for (int y = 0; y < h; y++) {
            int off = y * w;
            double dy = (y - cy);
            for (int x = 0; x < w; x++) {
                if ((pix[off + x] & 0xff) > 0) {
                    double dx = (x - cx);
                    double d2 = dx * dx + dy * dy;
                    if (d2 > r2) r2 = d2;
                }
            }
        }

        double maxR = 0.5 * Math.min(w, h);
        if (r2 > maxR * maxR) r2 = maxR * maxR;

        ByteProcessor out = new ByteProcessor(w, h);
        out.setValue(0); out.fill();
        out.setValue(255);

        for (int y = 0; y < h; y++) {
            double dy = (y - cy);
            for (int x = 0; x < w; x++) {
                double dx = (x - cx);
                if (dx * dx + dy * dy <= r2) out.set(x, y, 255);
            }
        }
        return out;
    }

    private RandomAccessibleInterval<BitType> stackToBits(final ImageStack maskStack) {
        final int w = maskStack.getWidth();
        final int h = maskStack.getHeight();
        final int nZ = maskStack.getSize();

        Img<BitType> maskBits = ArrayImgs.bits(w, h, nZ);
        net.imglib2.RandomAccess<BitType> ra = maskBits.randomAccess();

        for (int z = 1; z <= nZ; z++) {
            ByteProcessor bp = (ByteProcessor) maskStack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean v = (bp.get(x, y) & 0xff) > 0;
                    ra.setPosition(new long[]{x, y, z - 1});
                    ra.get().set(v);
                }
            }
        }
        return maskBits;
    }

    private Overlay makeBoundaryOverlayFromBinaryMask(ImagePlus maskImp) {
        Overlay ov = new Overlay();
        ThresholdToSelection tts = new ThresholdToSelection();

        int nZ = maskImp.getNSlices();
        int stride = 1;

        final boolean isHyper = maskImp.isHyperStack();

        for (int z = 1; z <= nZ; z += stride) {
            ImageProcessor ip = maskImp.getStack().getProcessor(z);
            ByteProcessor bp8 = (ByteProcessor) ip.convertToByte(false);

            for (int y = 0; y < bp8.getHeight(); y++) {
                for (int x = 0; x < bp8.getWidth(); x++) {
                    int v = bp8.get(x, y) & 0xff;
                    bp8.set(x, y, v > 0 ? 255 : 0);
                }
            }

            BinaryProcessor bin = new BinaryProcessor(bp8);
            bin.outline();

            bin.setThreshold(1, 255, ImageProcessor.NO_LUT_UPDATE);
            Roi roi = tts.convert(bin);

            if (roi != null) {
                if (isHyper) roi.setPosition(1, z, 1);
                else roi.setPosition(z);

                roi.setStrokeWidth(2.0);
                roi.setStrokeColor(new Color(255, 0, 0, 255));
                roi.setFillColor(new Color(0, 0, 0, 0));
                ov.add(roi);
            }

            IJ.wait(1);
        }

        return ov;
    }

    private void copyRoiPosition(final Roi src, final Roi dst) {
        if (src == null || dst == null) return;
        try {
            int c = 0, z = 0, t = 0;
            try { c = src.getCPosition(); } catch (Throwable ignored) {}
            try { z = src.getZPosition(); } catch (Throwable ignored) {}
            try { t = src.getTPosition(); } catch (Throwable ignored) {}
            if (c > 0 || z > 0 || t > 0) {
                try { dst.setPosition(c, z, t); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {}

        try {
            int pos = 0;
            try { pos = src.getPosition(); } catch (Throwable ignored) {}
            if (pos > 0) {
                try { dst.setPosition(pos); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private Roi ensureAreaRoiFromBoundary(final Roi roi) {
        try {
            if (roi == null) return null;

            if (roi.isArea()) return roi;

            try {
                ShapeRoi sr = new ShapeRoi(roi);
                Roi filled = sr.shapeToRoi();
                if (filled != null && filled.isArea()) {
                    copyRoiPosition(roi, filled);
                    return filled;
                }

                Roi[] rois = sr.getRois();
                if (rois != null && rois.length > 0) {
                    for (Roi r : rois) {
                        if (r != null && r.isArea()) {
                            copyRoiPosition(roi, r);
                            return r;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            FloatPolygon fp = roi.getFloatPolygon();
            if (fp == null || fp.npoints < 2) return null;

            int n = fp.npoints;

            boolean closed = (Math.round(fp.xpoints[0]) == Math.round(fp.xpoints[n - 1]))
                    && (Math.round(fp.ypoints[0]) == Math.round(fp.ypoints[n - 1]));

            int outN = closed ? n : (n + 1);
            int[] xs = new int[outN];
            int[] ys = new int[outN];

            for (int i = 0; i < n; i++) {
                xs[i] = Math.round(fp.xpoints[i]);
                ys[i] = Math.round(fp.ypoints[i]);
            }
            if (!closed) {
                xs[outN - 1] = xs[0];
                ys[outN - 1] = ys[0];
            }

            PolygonRoi poly = new PolygonRoi(xs, ys, outN, Roi.POLYGON);
            copyRoiPosition(roi, poly);

            if (poly.isArea()) return poly;

            try {
                ShapeRoi sr2 = new ShapeRoi(poly);
                Roi filled2 = sr2.shapeToRoi();
                if (filled2 != null && filled2.isArea()) {
                    copyRoiPosition(roi, filled2);
                    return filled2;
                }
            } catch (Throwable ignored) {}

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private ByteProcessor rasterizeInsideRoi(final Roi roi, final int w, final int h) {
        ByteProcessor bp = new ByteProcessor(w, h);
        bp.setValue(0); bp.fill();

        if (roi == null) return bp;

        Roi areaRoi = ensureAreaRoiFromBoundary(roi);
        if (areaRoi == null) return bp;

        try {
            bp.setValue(255);
            bp.setRoi(areaRoi);
            bp.fill(areaRoi);
            return bp;
        } catch (Throwable ignored) {}

        Rectangle b = areaRoi.getBounds();
        if (b == null) return bp;

        ImageProcessor m = areaRoi.getMask();
        if (m == null) {
            bp.setValue(255);
            bp.setRoi(areaRoi);
            bp.fill(areaRoi);
            return bp;
        }

        int bw = b.width;
        int bh = b.height;
        for (int yy = 0; yy < bh; yy++) {
            int y = b.y + yy;
            if (y < 0 || y >= h) continue;
            for (int xx = 0; xx < bw; xx++) {
                int x = b.x + xx;
                if (x < 0 || x >= w) continue;
                int v = m.get(xx, yy) & 0xff;
                if (v != 0) bp.set(x, y, 255);
            }
        }
        return bp;
    }

    private RandomAccessibleInterval<BitType> buildVOIFromCurrentRoiAllSlices(final ImagePlus imp) {
        Roi roi = imp.getRoi();
        if (roi == null) return null;

        final int w = imp.getWidth();
        final int h = imp.getHeight();
        final int nZ = imp.getNSlices();

        Img<BitType> out = ArrayImgs.bits(w, h, nZ);

        for (int z = 1; z <= nZ; z++) {
            ByteProcessor bp = rasterizeInsideRoi(roi, w, h);
            writeSliceToBits(out, bp, z);
        }

        if (isMaskEmpty(out)) return null;
        return out;
    }

    private int getSliceNumberFromName(final String name) {
        if (name == null) return 0;
        String s = name.trim();
        if (s.isEmpty()) return 0;

        int best = 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)\\b(?:z|slice|sl|plane)\\s*[:=#-]?\\s*(\\d+)\\b")
                    .matcher(s);
            while (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v > best) best = v;
            }
        } catch (Throwable ignored) {}

        if (best > 0) return best;

        try {
            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("(\\d+)(?!.*\\d)")
                    .matcher(s);
            if (m2.find()) return Integer.parseInt(m2.group(1));
        } catch (Throwable ignored) {}

        return 0;
    }

    private int getRoiSlicePosition(final ImagePlus imp, final Roi r) {
        int zPos = 0;

        try { zPos = r.getZPosition(); } catch (Throwable ignored) {}

        if (zPos <= 0) {
            int pos = 0;
            try { pos = r.getPosition(); } catch (Throwable ignored) {}
            if (pos > 0) {
                if (imp != null && imp.isHyperStack()) {
                    try {
                        int[] czt = imp.convertIndexToPosition(pos);
                        if (czt != null && czt.length >= 3) zPos = czt[1];
                        else zPos = 0;
                    } catch (Throwable ignored) {
                        zPos = 0;
                    }
                } else {
                    zPos = pos;
                }
            }
        }

        if (zPos <= 0) {
            try {
                String nm = null;
                try { nm = r.getName(); } catch (Throwable ignored) {}
                if (nm != null && !nm.trim().isEmpty()) {
                    int parsed = getSliceNumberFromName(nm);
                    if (parsed > 0) zPos = parsed;
                }
            } catch (Throwable ignored) {}
        }

        if (imp != null) {
            final int nZ = imp.getNSlices();
            if (zPos < 1 || zPos > nZ) zPos = 0;
        }

        return zPos;
    }

    private RandomAccessibleInterval<BitType> buildVOIFromRoiManager(final ImagePlus imp, final boolean interpolateMissingSlices) {
        RoiManager rm = getOrCreateRoiManagerVisible();
        if (rm == null) return null;

        Roi[] rois = rm.getRoisAsArray();
        if (rois == null || rois.length == 0) return null;

        final int w = imp.getWidth();
        final int h = imp.getHeight();
        final int nZ = imp.getNSlices();

        ByteProcessor[] perSlice = new ByteProcessor[nZ];

        for (Roi r0 : rois) {
            if (r0 == null) continue;

            Roi r = ensureAreaRoiFromBoundary(r0);
            if (r == null) continue;

            int zPos = getRoiSlicePosition(imp, r);

            if (zPos <= 0) {
                for (int z = 1; z <= nZ; z++) {
                    if (perSlice[z - 1] == null) perSlice[z - 1] = new ByteProcessor(w, h);
                    ByteProcessor bp = perSlice[z - 1];
                    ByteProcessor rmask = rasterizeInsideRoi(r, w, h);
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                        if ((rmask.get(x, y) & 0xff) > 0) bp.set(x, y, 255);
                    }
                }
            } else if (zPos >= 1 && zPos <= nZ) {
                if (perSlice[zPos - 1] == null) perSlice[zPos - 1] = new ByteProcessor(w, h);
                ByteProcessor bp = perSlice[zPos - 1];
                ByteProcessor rmask = rasterizeInsideRoi(r, w, h);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    if ((rmask.get(x, y) & 0xff) > 0) bp.set(x, y, 255);
                }
            }
        }

        if (interpolateMissingSlices) {
            int lastSeen = -1;
            for (int i = 0; i < nZ; i++) {
                if (perSlice[i] != null) lastSeen = i;
                else if (lastSeen >= 0) perSlice[i] = (ByteProcessor) perSlice[lastSeen].duplicate();
            }
            int nextSeen = -1;
            for (int i = nZ - 1; i >= 0; i--) {
                if (perSlice[i] != null) nextSeen = i;
                else if (nextSeen >= 0) perSlice[i] = (ByteProcessor) perSlice[nextSeen].duplicate();
            }
        }

        boolean any = false;
        for (int i = 0; i < nZ; i++) if (perSlice[i] != null) { any = true; break; }
        if (!any) return null;

        Img<BitType> out = ArrayImgs.bits(w, h, nZ);

        for (int z = 1; z <= nZ; z++) {
            ByteProcessor bp = perSlice[z - 1];
            if (bp == null) {
                bp = new ByteProcessor(w, h);
                bp.setValue(0); bp.fill();
            } else {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        bp.set(x, y, ((bp.get(x, y) & 0xff) > 0) ? 255 : 0);
                    }
                }
            }
            writeSliceToBits(out, bp, z);
        }

        if (isMaskEmpty(out)) return null;
        return out;
    }

    private RandomAccessibleInterval<BitType> toOuterShellPerSlice(final RandomAccessibleInterval<BitType> mask, final int thickness) {
        final long[] d = new long[3];
        mask.dimensions(d);
        final int w = (int) d[0];
        final int h = (int) d[1];
        final int nZ = (int) d[2];

        Img<BitType> out = ArrayImgs.bits(w, h, nZ);

        final net.imglib2.RandomAccess<BitType> raIn = mask.randomAccess();

        for (int z = 1; z <= nZ; z++) {
            ByteProcessor bp = new ByteProcessor(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean v = getBit(raIn, x, y, z - 1);
                    bp.set(x, y, v ? 255 : 0);
                }
            }

            ByteProcessor er = (ByteProcessor) bp.duplicate();
            erode2D(er, thickness);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = bp.get(x, y) & 0xff;
                    int b = er.get(x, y) & 0xff;
                    bp.set(x, y, (a > 0 && b == 0) ? 255 : 0);
                }
            }

            writeSliceToBits(out, bp, z);
        }
        return out;
    }

    private void writeSliceToBits(final Img<BitType> out, final ByteProcessor bp, final int z1based) {
        final int z0 = z1based - 1;
        final int w = bp.getWidth();
        final int h = bp.getHeight();

        final net.imglib2.RandomAccess<BitType> ra = out.randomAccess();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = (bp.get(x, y) & 0xff) > 0;
                ra.setPosition(new long[]{x, y, z0});
                ra.get().set(v);
            }
        }
    }

    private boolean getBit(final RandomAccessibleInterval<BitType> mask, final int x, final int y, final int z) {
        final net.imglib2.RandomAccess<BitType> ra = mask.randomAccess();
        ra.setPosition(new long[]{x, y, z});
        return ra.get().get();
    }

    private boolean getBit(final net.imglib2.RandomAccess<BitType> ra, final int x, final int y, final int z) {
        ra.setPosition(new long[]{x, y, z});
        return ra.get().get();
    }

    private boolean isMaskEmpty(final RandomAccessibleInterval<BitType> mask) {
        for (BitType v : Views.iterable(mask)) {
            if (v.get()) return false;
        }
        return true;
    }

    private void fillHoles2D(final ByteProcessor bp) {
        final int w = bp.getWidth();
        final int h = bp.getHeight();

        ArrayDeque<int[]> q = new ArrayDeque<>();

        for (int x = 0; x < w; x++) {
            if ((bp.get(x, 0) & 0xff) == 0) { bp.set(x, 0, 128); q.add(new int[]{x, 0}); }
            if ((bp.get(x, h - 1) & 0xff) == 0) { bp.set(x, h - 1, 128); q.add(new int[]{x, h - 1}); }
        }
        for (int y = 0; y < h; y++) {
            if ((bp.get(0, y) & 0xff) == 0) { bp.set(0, y, 128); q.add(new int[]{0, y}); }
            if ((bp.get(w - 1, y) & 0xff) == 0) { bp.set(w - 1, y, 128); q.add(new int[]{w - 1, y}); }
        }

        while (!q.isEmpty()) {
            int[] p = q.removeFirst();
            int px = p[0], py = p[1];

            int nx, ny;

            nx = px - 1; ny = py;
            if (nx >= 0 && (bp.get(nx, ny) & 0xff) == 0) { bp.set(nx, ny, 128); q.add(new int[]{nx, ny}); }

            nx = px + 1; ny = py;
            if (nx < w && (bp.get(nx, ny) & 0xff) == 0) { bp.set(nx, ny, 128); q.add(new int[]{nx, ny}); }

            nx = px; ny = py - 1;
            if (ny >= 0 && (bp.get(nx, ny) & 0xff) == 0) { bp.set(nx, ny, 128); q.add(new int[]{nx, ny}); }

            nx = px; ny = py + 1;
            if (ny < h && (bp.get(nx, ny) & 0xff) == 0) { bp.set(nx, ny, 128); q.add(new int[]{nx, ny}); }
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = bp.get(x, y) & 0xff;
                if (v == 0) bp.set(x, y, 255);
                else if (v == 128) bp.set(x, y, 0);
            }
        }
    }

    private void close2D(final ByteProcessor bp, final int iters) {
        BinaryProcessor bin = new BinaryProcessor(bp);
        for (int i = 0; i < iters; i++) bin.dilate();
        for (int i = 0; i < iters; i++) bin.erode();
    }

    private void erode2D(final ByteProcessor bp, final int iters) {
        BinaryProcessor bin = new BinaryProcessor(bp);
        for (int i = 0; i < iters; i++) bin.erode();
    }

    private void keepLargestComponent2D(final ByteProcessor bp) {
        final int w = bp.getWidth();
        final int h = bp.getHeight();
        final int size = w * h;
        final byte[] pix = (byte[]) bp.getPixels();

        final boolean[] visited = new boolean[size];
        int bestStart = -1;
        int bestCount = 0;

        ArrayDeque<Integer> q = new ArrayDeque<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (visited[idx]) continue;
                int v = pix[idx] & 0xff;
                if (v == 0) { visited[idx] = true; continue; }

                int count = 0;
                visited[idx] = true;
                q.add(idx);

                while (!q.isEmpty()) {
                    int cur = q.removeFirst();
                    count++;

                    int cx = cur % w;
                    int cy = cur / w;

                    int nx, ny, nidx;

                    nx = cx - 1; ny = cy;
                    if (nx >= 0) {
                        nidx = ny * w + nx;
                        if (!visited[nidx] && (pix[nidx] & 0xff) > 0) { visited[nidx] = true; q.add(nidx); }
                        else if (!visited[nidx]) visited[nidx] = true;
                    }

                    nx = cx + 1; ny = cy;
                    if (nx < w) {
                        nidx = ny * w + nx;
                        if (!visited[nidx] && (pix[nidx] & 0xff) > 0) { visited[nidx] = true; q.add(nidx); }
                        else if (!visited[nidx]) visited[nidx] = true;
                    }

                    nx = cx; ny = cy - 1;
                    if (ny >= 0) {
                        nidx = ny * w + nx;
                        if (!visited[nidx] && (pix[nidx] & 0xff) > 0) { visited[nidx] = true; q.add(nidx); }
                        else if (!visited[nidx]) visited[nidx] = true;
                    }

                    nx = cx; ny = cy + 1;
                    if (ny < h) {
                        nidx = ny * w + nx;
                        if (!visited[nidx] && (pix[nidx] & 0xff) > 0) { visited[nidx] = true; q.add(nidx); }
                        else if (!visited[nidx]) visited[nidx] = true;
                    }
                }

                if (count > bestCount) {
                    bestCount = count;
                    bestStart = idx;
                }
            }
        }

        if (bestStart < 0) {
            bp.setValue(0); bp.fill();
            return;
        }

        final byte[] orig = pix.clone();

        for (int i = 0; i < size; i++) pix[i] = 0;

        q.clear();
        q.add(bestStart);
        pix[bestStart] = (byte) 255;

        while (!q.isEmpty()) {
            int cur = q.removeFirst();
            int cx = cur % w;
            int cy = cur / w;

            int nx, ny, nidx;

            nx = cx - 1; ny = cy;
            if (nx >= 0) {
                nidx = ny * w + nx;
                if ((orig[nidx] & 0xff) > 0 && (pix[nidx] & 0xff) == 0) { pix[nidx] = (byte)255; q.add(nidx); }
            }

            nx = cx + 1; ny = cy;
            if (nx < w) {
                nidx = ny * w + nx;
                if ((orig[nidx] & 0xff) > 0 && (pix[nidx] & 0xff) == 0) { pix[nidx] = (byte)255; q.add(nidx); }
            }

            nx = cx; ny = cy - 1;
            if (ny >= 0) {
                nidx = ny * w + nx;
                if ((orig[nidx] & 0xff) > 0 && (pix[nidx] & 0xff) == 0) { pix[nidx] = (byte)255; q.add(nidx); }
            }

            nx = cx; ny = cy + 1;
            if (ny < h) {
                nidx = ny * w + nx;
                if ((orig[nidx] & 0xff) > 0 && (pix[nidx] & 0xff) == 0) { pix[nidx] = (byte)255; q.add(nidx); }
            }
        }
    }

    private double vxEff, vyEff, vzEff;
    private String unitsEff;

    private Roi[] getRoiPerSlice(final ImagePlus imp) {
        final int nZ = imp.getNSlices();
        Roi[] roiZ = new Roi[nZ];

        if ("ROI Manager (per-slice)".equals(userVoiSource)) {
            RoiManager rm = getOrCreateRoiManagerVisible();
            if (rm == null) return null;

            Roi[] rois = rm.getRoisAsArray();
            if (rois == null || rois.length == 0) return null;

            for (Roi r0 : rois) {
                if (r0 == null) continue;
                Roi r = ensureAreaRoiFromBoundary(r0);
                if (r == null) continue;

                int zPos = getRoiSlicePosition(imp, r);
                if (zPos >= 1 && zPos <= nZ) {
                    roiZ[zPos - 1] = r;
                } else {
                    for (int z = 0; z < nZ; z++) if (roiZ[z] == null) roiZ[z] = r;
                }
            }

            if (interpolateRoiManagerSlices) {
                int last = -1;
                for (int z = 0; z < nZ; z++) {
                    if (roiZ[z] != null) last = z;
                    else if (last >= 0) roiZ[z] = roiZ[last];
                }
                int next = -1;
                for (int z = nZ - 1; z >= 0; z--) {
                    if (roiZ[z] != null) next = z;
                    else if (next >= 0) roiZ[z] = roiZ[next];
                }
            }

            for (int z = 0; z < nZ; z++) if (roiZ[z] == null) return null;
            return roiZ;

        } else {
            Roi r0 = imp.getRoi();
            if (r0 == null) return null;

            Roi r = ensureAreaRoiFromBoundary(r0);
            if (r == null) return null;

            for (int z = 0; z < nZ; z++) roiZ[z] = r;
            return roiZ;
        }
    }

    private double computeGlobalThreshold(final RandomAccessibleInterval<? extends RealType<?>> src,
                                          final String poresAre, final double tol) {

        final Iterable<? extends RealType<?>> it = Views.iterable(src);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (RealType<?> v : it) {
            double d = v.getRealDouble();
            if (d < min) min = d;
            if (d > max) max = d;
        }

        final boolean poresAreBlack = "black".equalsIgnoreCase(poresAre);

        final boolean useAuto = (tol <= 0.0);
        final double thr;

        if (useAuto) {
            final int bins = 256;
            final int[] hist = new int[bins];
            final double range = max - min;
            final double inv = (range > 0) ? (1.0 / range) : 0.0;

            for (RealType<?> v : Views.iterable(src)) {
                double d = v.getRealDouble();
                int b = (range > 0) ? (int) Math.round((d - min) * inv * (bins - 1)) : 0;
                if (b < 0) b = 0;
                if (b >= bins) b = bins - 1;
                hist[b]++;
            }

            AutoThresholder at = new AutoThresholder();
            int tBin = at.getThreshold(AutoThresholder.Method.Otsu, hist);
            if (tBin < 0) tBin = 0;
            if (tBin > 255) tBin = 255;
            thr = (range > 0) ? (min + (tBin / 255.0) * range) : min;
        } else {
            double tolClamped = tol;
            if (tolClamped < 0.0) tolClamped = 0.0;
            if (tolClamped > 1.0) tolClamped = 1.0;

            final double eps = 1e-6;
            if (tolClamped <= 0.0) tolClamped = eps;
            if (tolClamped >= 1.0) tolClamped = 1.0 - eps;

            thr = poresAreBlack ? (min + tolClamped * (max - min))
                    : (max - tolClamped * (max - min));
        }

        IJ.log("[StrengthLawExtractor DEBUG] computeGlobalThreshold: min=" + min + " max=" + max + " thr=" + thr +
                " poresAre=" + poresAre + " useAuto=" + useAuto);

        return thr;
    }

    private static final class RoiThrStats {
        final double thr;
        final long roiCount;
        RoiThrStats(final double thr, final long roiCount) {
            this.thr = thr;
            this.roiCount = roiCount;
        }
    }

    private RoiThrStats computeThresholdFromRoiPixels(final ImagePlus imp,
                                                      final Roi[] roiZ,
                                                      final String poresAre,
                                                      final double tol) {
        final int w = imp.getWidth();
        final int h = imp.getHeight();
        final int nZ = imp.getNSlices();

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long roiCount = 0;

        for (int z = 1; z <= nZ; z++) {
            final Roi rz = (roiZ != null) ? roiZ[z - 1] : null;
            if (rz == null) continue;

            final ByteProcessor rmask = rasterizeInsideRoi(rz, w, h);
            final ImageProcessor ip = imp.getStack().getProcessor(z).convertToFloat();

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((rmask.get(x, y) & 0xff) == 0) continue;
                    roiCount++;
                    final double v = ip.getf(x, y);
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            if ((z & 7) == 0) IJ.wait(1);
        }

        if (roiCount <= 0) {
            IJ.log("[StrengthLawExtractor DEBUG] computeThresholdFromRoiPixels: roiCount=0 (empty ROI rasterization).");
            return new RoiThrStats(Double.NaN, 0);
        }

        final boolean poresAreBlack = "black".equalsIgnoreCase(poresAre);
        final boolean useAuto = (tol <= 0.0);
        final double thr;

        if (useAuto) {
            final int bins = 256;
            final int[] hist = new int[bins];
            final double range = max - min;
            final double inv = (range > 0) ? (1.0 / range) : 0.0;

            for (int z = 1; z <= nZ; z++) {
                final Roi rz = (roiZ != null) ? roiZ[z - 1] : null;
                if (rz == null) continue;

                final ByteProcessor rmask = rasterizeInsideRoi(rz, w, h);
                final ImageProcessor ip = imp.getStack().getProcessor(z).convertToFloat();

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if ((rmask.get(x, y) & 0xff) == 0) continue;
                        final double d = ip.getf(x, y);
                        int b = (range > 0) ? (int) Math.round((d - min) * inv * (bins - 1)) : 0;
                        if (b < 0) b = 0;
                        if (b >= bins) b = bins - 1;
                        hist[b]++;
                    }
                }
                if ((z & 7) == 0) IJ.wait(1);
            }

            AutoThresholder at = new AutoThresholder();
            int tBin = at.getThreshold(AutoThresholder.Method.Otsu, hist);
            if (tBin < 0) tBin = 0;
            if (tBin > 255) tBin = 255;
            thr = (range > 0) ? (min + (tBin / 255.0) * range) : min;
        } else {
            double tolClamped = tol;
            if (tolClamped < 0.0) tolClamped = 0.0;
            if (tolClamped > 1.0) tolClamped = 1.0;

            final double eps = 1e-6;
            if (tolClamped <= 0.0) tolClamped = eps;
            if (tolClamped >= 1.0) tolClamped = 1.0 - eps;

            thr = poresAreBlack ? (min + tolClamped * (max - min))
                    : (max - tolClamped * (max - min));
        }

        IJ.log("[StrengthLawExtractor DEBUG] computeThresholdFromRoiPixels: roiCount=" + roiCount +
                " min=" + min + " max=" + max + " thr=" + thr + " poresAre=" + poresAre + " useAuto=" + useAuto);
        return new RoiThrStats(thr, roiCount);
    }

    private RoiThresholdResult thresholdInsideRoi(final ImagePlus imp,
                                                 final Img<? extends RealType<?>> img,
                                                 final Roi[] roiZ,
                                                 final String poresAre,
                                                 final double tol) {

        final int w = imp.getWidth();
        final int h = imp.getHeight();
        final int nZ = imp.getNSlices();

        final Img<BitType> pores = ArrayImgs.bits(w, h, nZ);
        final Img<BitType> solid = ArrayImgs.bits(w, h, nZ);

        final net.imglib2.RandomAccess<BitType> raP = pores.randomAccess();
        final net.imglib2.RandomAccess<BitType> raS = solid.randomAccess();

        final RoiThrStats ts = computeThresholdFromRoiPixels(imp, roiZ, poresAre, tol);
        final double thr = ts.thr;
        final boolean poresAreBlack = "black".equalsIgnoreCase(poresAre);

        long roiCount = 0;
        long poreCount = 0;
        long solidCount = 0;

        for (int z = 1; z <= nZ; z++) {
            final Roi rz = (roiZ != null) ? roiZ[z - 1] : null;
            if (rz == null) continue;

            final ByteProcessor rmask = rasterizeInsideRoi(rz, w, h);
            final ImageProcessor ip = imp.getStack().getProcessor(z).convertToFloat();
            final int z0 = z - 1;

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((rmask.get(x, y) & 0xff) == 0) continue;

                    roiCount++;

                    final double v = ip.getf(x, y);
                    final boolean isPore = poresAreBlack ? (v <= thr) : (v >= thr);
                    final boolean isSolid = !isPore;

                    raP.setPosition(new long[]{x, y, z0});
                    raS.setPosition(new long[]{x, y, z0});
                    raP.get().set(isPore);
                    raS.get().set(isSolid);

                    if (isPore) poreCount++;
                    else solidCount++;
                }
            }

            if ((z & 7) == 0) IJ.wait(1);
        }

        IJ.log("[StrengthLawExtractor DEBUG] thresholdInsideRoi: roi=" + roiCount + " pore=" + poreCount + " solid=" + solidCount);
        return new RoiThresholdResult(pores, solid, roiCount, poreCount, solidCount);
    }

    public void onCompute() {
        scheduleDemodalizeCommandDialogs();
        bringOurDialogsToFront();

        final boolean headless = (ui == null) || ui.isHeadless();
        final ProgressWindow pw = headless ? null : new ProgressWindow("StrengthLawExtractor: Computing");

        Thread worker = new Thread(() -> {
            try {
                if (pw != null) pw.set(2, "Checking image");
                ImagePlus imp = IJ.getImage();
                if (imp == null || imp.getNSlices() < 2) {
                    if (pw != null) pw.close();
                    SwingUtilities.invokeLater(() -> IJ.error("Open a 3D stack."));
                    return;
                }

                vxEff = vx; vyEff = vy; vzEff = vz; unitsEff = units;
                if (useImageCalibration) {
                    Calibration cal = imp.getCalibration();
                    if (cal != null) {
                        double px = cal.pixelWidth  > 0 ? cal.pixelWidth  : vxEff;
                        double py = cal.pixelHeight > 0 ? cal.pixelHeight : vyEff;
                        double pz = cal.pixelDepth  > 0 ? cal.pixelDepth  : vzEff;
                        String u = cal.getXUnit() != null && !cal.getXUnit().isEmpty()
                                ? cal.getXUnit()
                                : (cal.getUnit() != null && !cal.getUnit().isEmpty() ? cal.getUnit() : unitsEff);
                        vxEff = px; vyEff = py; vzEff = pz; unitsEff = u;
                    }
                }

                if (pw != null) pw.set(10, "Converting");
                @SuppressWarnings("unchecked")
                Img<RealType<?>> img = (Img<RealType<?>>) ImageJFunctions.wrapReal(imp);

                metrics.clear();
                mesh = null;

                final boolean doMesh = "Mesh (accurate, heavier)".equals(mode);

                if (!"Auto ROI".equals(voiMode)) {

                    if (pw != null) pw.set(15, "Getting user ROI");
                    Roi[] roiZ = getRoiPerSlice(imp);
                    if (roiZ == null) {
                        if (pw != null) pw.close();
                        SwingUtilities.invokeLater(() -> IJ.error("User ROI not found. Draw ROI or add ROIs to ROI Manager."));
                        return;
                    }

                    if (pw != null) pw.set(20, "Thresholding inside ROI");
                    RoiThresholdResult tr = thresholdInsideRoi(imp, img, roiZ, poresAre, fracTol);
                    if (!ensureNonEmptyRoiCount("ROI", tr.roiVoxels)) {
                        if (pw != null) pw.close();
                        return;
                    }

                    Img<BitType> poreROIImg = tr.pores;
                    Img<BitType> solidROIImg = tr.solid;

                    logMaskStats("Pores (ROI-built)", poreROIImg);
                    logMaskStats("Solid (ROI-built)", solidROIImg);

                    if (padSolid) solidROIImg = (Img<BitType>) MeshUtils.padSolid(solidROIImg);

                    bin01 = poreROIImg;

                    if (clearBorderVoxels) {
                        final Img<BitType> solidBefore = materializeBits(solidROIImg);
                        clearBorderInPlace(solidROIImg);
                        final long after = countTrue(solidROIImg);
                        if (after <= 0) {
                            IJ.log("[StrengthLawExtractor DEBUG] clearBorder erased solid completely; reverting clearBorder.");
                            solidROIImg = solidBefore;
                        }
                    }

                    logMaskStats("Solid (after clearBorder)", solidROIImg);

                    if (pw != null) pw.set(35, "Porosity");
                    double porosity = (tr.roiVoxels > 0) ? (tr.poreVoxels / (double) tr.roiVoxels) : 0.0;
                    double porosityPct = 100.0 * porosity;

                    if (pw != null) pw.set(45, "Voxel metrics");
                    MeshUtils.VoxelCounts counts = MeshUtils.countVoxelConfigurations(solidROIImg);
                    if (counts != null) {
                        IJ.log("[StrengthLawExtractor DEBUG] MeshUtils.VoxelCounts = " + counts.toString());
                    } else {
                        IJ.log("[StrengthLawExtractor DEBUG] MeshUtils.VoxelCounts = null");
                    }

                    double imcVoxel  = MeshUtils.voxelIntegratedMeanCurvatureFromCounts(counts, vxEff, vyEff, vzEff);
                    long   eulerVoxel= MeshUtils.voxelEulerFromCounts(counts);

                    double areaMesh = Double.NaN;

                    if (doMesh) {
                        if (pw != null) pw.set(60, "Meshing");
                        double iso = 0.5;
                        mesh = (Mesh) ops.run(Ops.Geometric.MarchingCubes.class, solidROIImg, Double.valueOf(iso));

                        if (pw != null) pw.set(80, "Mesh area");
                        areaMesh = MeshUtils.surfaceArea(mesh, vzEff, vyEff, vxEff);
                    }

                    metrics.put("Porosity (%)", porosityPct);
                    if (doMesh) metrics.put("Surface Area (" + unitsEff + "²)", areaMesh);
                    else metrics.put("Surface Area (" + unitsEff + "²)", Double.NaN);
                    metrics.put("Integrated Mean Curvature (" + unitsEff + ")", imcVoxel);
                    metrics.put("Euler Characteristic", (double) eulerVoxel);

                    metrics.put("[DEBUG] ROI voxels", (double) tr.roiVoxels);
                    metrics.put("[DEBUG] Pore voxels", (double) tr.poreVoxels);
                    metrics.put("[DEBUG] Solid voxels", (double) tr.solidVoxels);

                    if (pw != null) pw.set(95, "Table");
                    ResultsTable rt = new ResultsTable();
                    rt.incrementCounter();
                    for (String k : metrics.keySet()) {
                        Double v = metrics.get(k);
                        rt.addValue(k, v == null ? Double.NaN : v.doubleValue());
                    }
                    table = rt;

                    SwingUtilities.invokeLater(() -> {
                        try {
                            table.show("StrengthLawExtractor Results");
                        } catch (Throwable t) {
                            IJ.handleException(t);
                        }
                    });

                    if (pw != null) pw.set(100, "Done");
                    return;
                }

                if (pw != null) pw.set(15, "Building ROI");
                specimenVOI = buildSpecimenVOI(imp, img);
                if (specimenVOI == null) {
                    if (pw != null) pw.close();
                    return;
                }

                logMaskStats("ROI (full)", specimenVOI);
                if (!ensureNonEmpty("ROI", specimenVOI)) {
                    if (pw != null) pw.close();
                    return;
                }

                if (pw != null) pw.set(20, "Thresholding");
                RandomAccessibleInterval<BitType> binPore = thresholdView(img, poresAre, fracTol);
                logMaskStats("Pores (full)", binPore);

                RandomAccessibleInterval<BitType> solid =
                        Converters.convert(binPore, (in, out) -> out.set(!in.get()), new BitType());
                logMaskStats("Solid (full)", solid);

                if (padSolid) solid = MeshUtils.padSolid(solid);

                binPore = andMasks(binPore, specimenVOI);
                solid   = andMasks(solid, specimenVOI);
                logMaskStats("Pores (masked)", binPore);
                logMaskStats("Solid (masked)", solid);

                bin01 = binPore;

                final long[][] bounds = computeTrueBoundingBox(specimenVOI, 3);
                if (bounds == null) {
                    IJ.log("[StrengthLawExtractor DEBUG] bounds=null");
                } else {
                    IJ.log("[StrengthLawExtractor DEBUG] bounds min=(" + bounds[0][0]+","+bounds[0][1]+","+bounds[0][2] +
                            ") max=(" + bounds[1][0]+","+bounds[1][1]+","+bounds[1][2] + ")");
                }

                final RandomAccessibleInterval<BitType> binPoreROI =
                        (bounds != null) ? Views.interval(binPore, bounds[0], bounds[1]) : binPore;
                final RandomAccessibleInterval<BitType> solidROI =
                        (bounds != null) ? Views.interval(solid,   bounds[0], bounds[1]) : solid;
                final RandomAccessibleInterval<BitType> voiROI =
                        (bounds != null) ? Views.interval(specimenVOI, bounds[0], bounds[1]) : specimenVOI;

                logMaskStats("ROI (cropped)", voiROI);
                logMaskStats("Pores (cropped)", binPoreROI);
                logMaskStats("Solid (cropped)", solidROI);

                final RandomAccessibleInterval<BitType> voiROI0   = Views.zeroMin(voiROI);
                final RandomAccessibleInterval<BitType> poreROI0  = Views.zeroMin(binPoreROI);
                final RandomAccessibleInterval<BitType> solidROI0 = Views.zeroMin(solidROI);

                final Img<BitType> voiROIImg   = materializeBits(voiROI0);
                final Img<BitType> poreROIImg  = materializeBits(poreROI0);
                final Img<BitType> solidROIImg = materializeBits(solidROI0);

                logMaskStats("ROI (materialized)", voiROIImg);
                logMaskStats("Pores (materialized)", poreROIImg);
                logMaskStats("Solid (materialized)", solidROIImg);

                if (!ensureNonEmpty("ROI (materialized)", voiROIImg)) {
                    if (pw != null) pw.close();
                    return;
                }
                if (!ensureNonEmpty("Solid (materialized)", solidROIImg)) {
                    if (pw != null) pw.close();
                    return;
                }

                if (clearBorderVoxels) {
                    final Img<BitType> solidBefore = materializeBits(solidROIImg);
                    clearBorderInPlace(solidROIImg);
                    final long after = countTrue(solidROIImg);
                    if (after <= 0) {
                        IJ.log("[StrengthLawExtractor DEBUG] clearBorder erased solid completely; reverting clearBorder.");
                        for (BitType v : Views.iterable(solidROIImg)) v.set(false);
                        final Cursor<BitType> cSrc = Views.iterable(solidBefore).localizingCursor();
                        final net.imglib2.RandomAccess<BitType> raOut = solidROIImg.randomAccess();
                        while (cSrc.hasNext()) {
                            BitType v = cSrc.next();
                            raOut.setPosition(new long[]{cSrc.getLongPosition(0), cSrc.getLongPosition(1), cSrc.getLongPosition(2)});
                            raOut.get().set(v.get());
                        }
                    }
                }

                logMaskStats("Solid (after clearBorder)", solidROIImg);

                if (pw != null) pw.set(35, "Porosity");
                double porosity = computePorosityWithinVOI(poreROIImg, voiROIImg);
                double porosityPct = 100.0 * porosity;

                metrics.clear();

                if (pw != null) pw.set(45, "Voxel metrics");
                MeshUtils.VoxelCounts counts = MeshUtils.countVoxelConfigurations(solidROIImg);
                if (counts != null) {
                    IJ.log("[StrengthLawExtractor DEBUG] MeshUtils.VoxelCounts = " + counts.toString());
                } else {
                    IJ.log("[StrengthLawExtractor DEBUG] MeshUtils.VoxelCounts = null");
                }

                double imcVoxel  = MeshUtils.voxelIntegratedMeanCurvatureFromCounts(counts, vxEff, vyEff, vzEff);
                long   eulerVoxel= MeshUtils.voxelEulerFromCounts(counts);

                double areaMesh = Double.NaN;
                mesh = null;

                if (doMesh) {
                    if (pw != null) pw.set(60, "Meshing");
                    double iso = 0.5;
                    mesh = (Mesh) ops.run(Ops.Geometric.MarchingCubes.class, solidROIImg, Double.valueOf(iso));

                    if (pw != null) pw.set(80, "Mesh area");
                    areaMesh = MeshUtils.surfaceArea(mesh, vzEff, vyEff, vxEff);
                }

                metrics.put("Porosity (%)", porosityPct);
                if (doMesh) metrics.put("Surface Area (" + unitsEff + "²)", areaMesh);
                else metrics.put("Surface Area (" + unitsEff + "²)", Double.NaN);
                metrics.put("Integrated Mean Curvature (" + unitsEff + ")", imcVoxel);
                metrics.put("Euler Characteristic", (double) eulerVoxel);

                metrics.put("[DEBUG] ROI voxels", (double) countTrue(voiROIImg));
                metrics.put("[DEBUG] Pore voxels", (double) countTrue(poreROIImg));
                metrics.put("[DEBUG] Solid voxels", (double) countTrue(solidROIImg));

                if (pw != null) pw.set(95, "Table");
                ResultsTable rt = new ResultsTable();
                rt.incrementCounter();
                for (String k : metrics.keySet()) {
                    Double v = metrics.get(k);
                    rt.addValue(k, v == null ? Double.NaN : v.doubleValue());
                }
                table = rt;

                SwingUtilities.invokeLater(() -> {
                    try {
                        table.show("StrengthLawExtractor Results");
                    } catch (Throwable t) {
                        IJ.handleException(t);
                    }
                });

                if (pw != null) pw.set(100, "Done");
            } catch (Throwable t) {
                IJ.handleException(t);
            } finally {
                if (pw != null) pw.close();
                bringOurDialogsToFront();
            }
        }, "StrengthLawExtractor-Compute");

        worker.setDaemon(true);
        worker.start();
    }

    private double computePorosityWithinVOI(final RandomAccessibleInterval<BitType> pores,
                                            final RandomAccessibleInterval<BitType> voi)
    {
        final RandomAccessibleInterval<BitType> voiAligned = Views.interval(voi, pores);
        final RandomAccessibleInterval<Pair<BitType, BitType>> paired =
                Views.interval(Views.pair(pores, voiAligned), pores);

        long total = 0, poreCount = 0;
        for (Pair<BitType, BitType> p : Views.iterable(paired)) {
            if (p.getB().get()) {
                total++;
                if (p.getA().get()) poreCount++;
            }
        }
        return total > 0 ? (poreCount / (double) total) : 0.0;
    }

    private long[][] computeTrueBoundingBox(final RandomAccessibleInterval<BitType> mask, final int margin) {
        if (mask == null) return null;

        final long[] dims = new long[3];
        mask.dimensions(dims);

        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;

        final Cursor<BitType> c = Views.iterable(mask).localizingCursor();
        boolean any = false;

        while (c.hasNext()) {
            final BitType v = c.next();
            if (!v.get()) continue;
            any = true;
            final long x = c.getLongPosition(0);
            final long y = c.getLongPosition(1);
            final long z = c.getLongPosition(2);

            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        if (!any) return null;

        final long m = Math.max(0, margin);

        minX = Math.max(0, minX - m);
        minY = Math.max(0, minY - m);
        minZ = Math.max(0, minZ - m);

        maxX = Math.min(dims[0] - 1, maxX + m);
        maxY = Math.min(dims[1] - 1, maxY + m);
        maxZ = Math.min(dims[2] - 1, maxZ + m);

        return new long[][]{ new long[]{minX, minY, minZ}, new long[]{maxX, maxY, maxZ} };
    }

    private RandomAccessibleInterval<BitType> thresholdView(
            final RandomAccessibleInterval<? extends RealType<?>> src,
            String poresAre, double tol) {

        final Iterable<? extends RealType<?>> it = Views.iterable(src);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (RealType<?> v : it) {
            double d = v.getRealDouble();
            if (d < min) min = d;
            if (d > max) max = d;
        }

        final boolean poresAreBlack = "black".equalsIgnoreCase(poresAre);

        final boolean useAuto = (tol <= 0.0);
        final double thr;

        if (useAuto) {
            final int bins = 256;
            final int[] hist = new int[bins];
            final double range = max - min;
            final double inv = (range > 0) ? (1.0 / range) : 0.0;

            for (RealType<?> v : Views.iterable(src)) {
                double d = v.getRealDouble();
                int b = (range > 0) ? (int) Math.round((d - min) * inv * (bins - 1)) : 0;
                if (b < 0) b = 0;
                if (b >= bins) b = bins - 1;
                hist[b]++;
            }

            AutoThresholder at = new AutoThresholder();
            int tBin = at.getThreshold(AutoThresholder.Method.Otsu, hist);
            if (tBin < 0) tBin = 0;
            if (tBin > 255) tBin = 255;
            thr = (range > 0) ? (min + (tBin / 255.0) * range) : min;
        } else {
            double tolClamped = tol;
            if (tolClamped < 0.0) tolClamped = 0.0;
            if (tolClamped > 1.0) tolClamped = 1.0;

            final double eps = 1e-6;
            if (tolClamped <= 0.0) tolClamped = eps;
            if (tolClamped >= 1.0) tolClamped = 1.0 - eps;

            thr = poresAreBlack ? (min + tolClamped * (max - min))
                    : (max - tolClamped * (max - min));
        }

        IJ.log("[StrengthLawExtractor DEBUG] thresholdView: min=" + min + " max=" + max + " thr=" + thr +
                " poresAre=" + poresAre + " useAuto=" + useAuto);

        final net.imglib2.converter.Converter<RealType<?>, BitType> conv =
                new net.imglib2.converter.Converter<RealType<?>, BitType>() {
                    @Override
                    public void convert(final RealType<?> in, final BitType out) {
                        final double val = in.getRealDouble();
                        final boolean isPore = poresAreBlack ? (val <= thr) : (val >= thr);
                        out.set(isPore);
                    }
                };

        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<RealType<?>> rai =
                (RandomAccessibleInterval<RealType<?>>) src;

        return Converters.convert(rai, conv, new BitType());
    }

    private static RandomAccessibleInterval<BitType> clearBorder(final RandomAccessibleInterval<BitType> in){
        final long[] d = new long[3];
        in.dimensions(d);
        final net.imglib2.RandomAccess<BitType> ra = in.randomAccess();
        for (long y=0; y<d[1]; y++){
            for (long x=0; x<d[0]; x++){
                ra.setPosition(new long[]{x,y,0}); ra.get().set(false);
                ra.setPosition(new long[]{x,y,d[2]-1}); ra.get().set(false);
            }
        }
        for (long z=0; z<d[2]; z++){
            for (long x=0; x<d[0]; x++){
                ra.setPosition(new long[]{x,0,z}); ra.get().set(false);
                ra.setPosition(new long[]{x,d[1]-1,z}); ra.get().set(false);
            }
        }
        for (long z=0; z<d[2]; z++){
            for (long y=0; y<d[1]; y++){
                ra.setPosition(new long[]{0,y,z}); ra.get().set(false);
                ra.setPosition(new long[]{d[0]-1,y,z}); ra.get().set(false);
            }
        }
        return in;
    }

    private static void clearBorderInPlace(final RandomAccessibleInterval<BitType> in) {
        clearBorder(in);
    }

    private RandomAccessibleInterval<BitType> andMasks(
            final RandomAccessibleInterval<BitType> a,
            final RandomAccessibleInterval<BitType> b)
    {
        final RandomAccessibleInterval<BitType> bAligned = Views.interval(b, a);

        final long[] d = new long[3];
        a.dimensions(d);
        final Img<BitType> out = ArrayImgs.bits(d[0], d[1], d[2]);

        final Cursor<BitType> ca = Views.iterable(a).localizingCursor();
        final net.imglib2.RandomAccess<BitType> rb = bAligned.randomAccess();
        final net.imglib2.RandomAccess<BitType> ro = out.randomAccess();

        while (ca.hasNext()) {
            final BitType va = ca.next();
            final long x = ca.getLongPosition(0);
            final long y = ca.getLongPosition(1);
            final long z = ca.getLongPosition(2);

            rb.setPosition(new long[]{x, y, z});
            ro.setPosition(new long[]{x, y, z});
            ro.get().set(va.get() && rb.get().get());
        }

        return out;
    }

    private Img<BitType> materializeBits(final RandomAccessibleInterval<BitType> src) {
        final long[] d = new long[3];
        src.dimensions(d);
        final int w = (int) d[0];
        final int h = (int) d[1];
        final int z = (int) d[2];

        Img<BitType> out = ArrayImgs.bits(w, h, z);

        final Cursor<BitType> cSrc = Views.iterable(src).localizingCursor();
        final net.imglib2.RandomAccess<BitType> raOut = out.randomAccess();

        while (cSrc.hasNext()) {
            BitType v = cSrc.next();
            raOut.setPosition(new long[]{
                    cSrc.getLongPosition(0),
                    cSrc.getLongPosition(1),
                    cSrc.getLongPosition(2)
            });
            raOut.get().set(v.get());
        }
        return out;
    }

    public void onExport() {
        scheduleDemodalizeCommandDialogs();
        bringOurDialogsToFront();

        Thread worker = new Thread(() -> {
            try {
                if (resultsCSV != null) {
                    exportResultsCsv(resultsCSV);
                }

                if (!"None".equals(exportFormat)) {
                    if ("STL (mesh)".equals(exportFormat)) {
                        if (meshSTL == null) {
                            SwingUtilities.invokeLater(() -> IJ.error("Export format is STL (mesh) but no output .stl file was selected."));
                        } else if (mesh == null) {
                            SwingUtilities.invokeLater(() -> IJ.error("No mesh is available. Compute in 'Mesh (accurate, heavier)' mode first."));
                        } else {
                            SwingUtilities.invokeLater(() -> IJ.error("Mesh export is not implemented in this build. Add a mesh writer for net.imagej.mesh and write to meshSTL."));
                        }
                    } else if ("OBJ wireframe (mesh)".equals(exportFormat) || "OBJ wireframe (voxel)".equals(exportFormat)) {
                        SwingUtilities.invokeLater(() -> IJ.error("OBJ export is not implemented in this build."));
                    }
                }
            } catch (Throwable t) {
                IJ.handleException(t);
            } finally {
                bringOurDialogsToFront();
            }
        }, "StrengthLawExtractor-Export");

        worker.setDaemon(true);
        worker.start();
    }

    private void exportResultsCsv(final File outFile) throws Exception {
        final ResultsTable rt = this.table;
        if (rt == null) {
            SwingUtilities.invokeLater(() -> IJ.error("No results table available. Compute first."));
            return;
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outFile))) {
            for (int c = 0; c < rt.getLastColumn() + 1; c++) {
                String head = rt.getColumnHeading(c);
                if (head != null && !head.trim().isEmpty()) {
                    pw.print(head);
                    pw.print(c < rt.getLastColumn() ? "," : "");
                }
            }
            pw.println();

            int rows = rt.getCounter();
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < rt.getLastColumn() + 1; c++) {
                    String head = rt.getColumnHeading(c);
                    if (head == null || head.trim().isEmpty()) continue;
                    double v = rt.getValueAsDouble(c, r);
                    pw.print(Double.isNaN(v) ? "" : Double.toString(v));
                    pw.print(c < rt.getLastColumn() ? "," : "");
                }
                pw.println();
            }
        }

        SwingUtilities.invokeLater(() -> IJ.showStatus("Saved results: " + outFile.getAbsolutePath()));
    }

    private ImagePlus bitMaskToBinaryByteImp(final RandomAccessibleInterval<BitType> mask, final String title) {
        final long[] d = new long[3];
        mask.dimensions(d);
        final int w = (int) d[0];
        final int h = (int) d[1];
        final int nZ = (int) d[2];

        ImageStack st = new ImageStack(w, h);

        final net.imglib2.RandomAccess<BitType> ra = mask.randomAccess();

        for (int z = 0; z < nZ; z++) {
            ByteProcessor bp = new ByteProcessor(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bp.set(x, y, getBit(ra, x, y, z) ? 255 : 0);
                }
            }
            st.addSlice(bp);
        }
        ImagePlus out = new ImagePlus(title, st);
        out.setDimensions(1, nZ, 1);
        return out;
    }

    private ImagePlus maskedOriginalPreview(final ImagePlus src, final RandomAccessibleInterval<BitType> mask, final String title) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        final int nZ = src.getNSlices();

        ImageStack st = new ImageStack(w, h);

        final net.imglib2.RandomAccess<BitType> ra = mask.randomAccess();

        for (int z = 1; z <= nZ; z++) {
            ImageProcessor ip = src.getStack().getProcessor(z);
            ImageProcessor outIp = ip.duplicate();

            int z0 = z - 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!getBit(ra, x, y, z0)) {
                        outIp.set(x, y, 0);
                    }
                }
            }

            st.addSlice(outIp);
            if ((z & 7) == 0) IJ.wait(1);
        }

        ImagePlus out = new ImagePlus(title, st);
        out.setDimensions(1, nZ, 1);
        out.setCalibration(src.getCalibration());
        return out;
    }

    private void scheduleDemodalizeCommandDialogs() {
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    demodalizeCommandDialogsInternal(false);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void demodalizeCommandDialogsInternal(boolean allowHideShow) {
        try {
            Window[] ws = Window.getWindows();
            if (ws == null) return;

            for (Window w : ws) {
                if (!(w instanceof Dialog)) continue;
                Dialog d = (Dialog) w;

                if (!isOurDialog(d)) continue;

                try {
                    if (d.getModalityType() != Dialog.ModalityType.MODELESS) {
                        d.setModalityType(Dialog.ModalityType.MODELESS);
                    }
                } catch (Throwable ignored) {}

                try {
                    d.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
                } catch (Throwable ignored) {}

                if (d instanceof JDialog) {
                    try {
                        if (((JDialog) d).isModal()) ((JDialog) d).setModal(false);
                    } catch (Throwable ignored) {}
                    try {
                        ((JDialog) d).setAutoRequestFocus(false); 
                    } catch (Throwable ignored) {}
                } else {
                    try {
                        d.setAutoRequestFocus(false);
                    } catch (Throwable ignored) {}
                }

                try { d.setAlwaysOnTop(keepPluginOnTop); } catch (Throwable ignored) {}

                try { d.setFocusableWindowState(true); } catch (Throwable ignored) {}

                try {
                    if (!d.isVisible()) d.setVisible(true);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private boolean isOurDialog(final Dialog d) {
        try {
            if (!(d instanceof JDialog)) return false;
            Container c = ((JDialog) d).getContentPane();
            if (c == null) return false;
            return containsButtonWithText(c, "Acquire / Update User ROI")
                    || containsButtonWithText(c, "Preview ROI")
                    || containsButtonWithText(c, "Compute Features");
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean containsButtonWithText(final Container root, final String text) {
        try {
            for (Component comp : root.getComponents()) {
                if (comp instanceof AbstractButton) {
                    String t = ((AbstractButton) comp).getText();
                    if (t != null && t.trim().equals(text)) return true;
                }
                if (comp instanceof Container) {
                    if (containsButtonWithText((Container) comp, text)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static final class ModelessPrompt {
        private final JDialog dlg;
        private final CountDownLatch latch;
        private final StrengthLawExtractor owner;

        ModelessPrompt(String title, String message, CountDownLatch latch, StrengthLawExtractor owner) {
            this.latch = latch;
            this.owner = owner;
            dlg = new JDialog((Frame) null, title, false);
            dlg.setModal(false);
            dlg.setAlwaysOnTop(false);

            try { dlg.setModalityType(Dialog.ModalityType.MODELESS); } catch (Throwable ignored) {}  
            try { dlg.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE); } catch (Throwable ignored) {}  
            try { dlg.setAutoRequestFocus(false); } catch (Throwable ignored) {}  

            JTextArea ta = new JTextArea(message);
            ta.setEditable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setBackground(dlg.getBackground());

            JScrollPane sp = new JScrollPane(ta);
            sp.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            sp.setPreferredSize(new Dimension(560, 190));

            JButton done = new JButton("Done");
            done.addActionListener(e -> {
                try { latch.countDown(); } catch (Throwable ignored) {}
                dlg.setVisible(false);
                dlg.dispose();
                try { if (owner != null) owner.bringOurDialogsToFront(); } catch (Throwable ignored) {}
            });

            JPanel btn = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btn.add(done);

            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            content.add(sp, BorderLayout.CENTER);
            content.add(btn, BorderLayout.SOUTH);

            dlg.setContentPane(content);
            dlg.pack();
            dlg.setLocationRelativeTo(null);
        }

        void show() {
            SwingUtilities.invokeLater(() -> {
                dlg.setVisible(true);
                dlg.toFront();
            });
        }

        void close() {
            SwingUtilities.invokeLater(() -> {
                if (dlg.isDisplayable()) {
                    dlg.setVisible(false);
                    dlg.dispose();
                }
                try { if (owner != null) owner.bringOurDialogsToFront(); } catch (Throwable ignored) {}
            });
        }
    }

    static final class ProgressWindow {
        private final JDialog dlg;
        private final JProgressBar bar;
        private final JLabel pctLabel;
        private final JLabel statusLabel;

        ProgressWindow(String title) {
            dlg = new JDialog((Frame) null, title, false);
            bar = new JProgressBar(0, 100);
            bar.setStringPainted(false);
            pctLabel = new JLabel("0 %");
            pctLabel.setHorizontalAlignment(SwingConstants.RIGHT);
            pctLabel.setPreferredSize(new Dimension(50, pctLabel.getPreferredSize().height));
            JPanel topRow = new JPanel(new BorderLayout(8, 0));
            topRow.add(bar, BorderLayout.CENTER);
            topRow.add(pctLabel, BorderLayout.EAST);
            statusLabel = new JLabel("Starting...");
            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            content.add(topRow, BorderLayout.NORTH);
            content.add(statusLabel, BorderLayout.CENTER);
            dlg.setContentPane(content);
            dlg.setAlwaysOnTop(false);

            try { dlg.setModalityType(Dialog.ModalityType.MODELESS); } catch (Throwable ignored) {}  
            try { dlg.setModal(false); } catch (Throwable ignored) {}  
            try { dlg.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE); } catch (Throwable ignored) {}  
            try { dlg.setAutoRequestFocus(false); } catch (Throwable ignored) {}  

            dlg.pack();
            dlg.setSize(520, dlg.getHeight());
            dlg.setLocationRelativeTo(null);
            if (SwingUtilities.isEventDispatchThread()) {
                dlg.setVisible(true);
                dlg.toFront();
            } else {
                SwingUtilities.invokeLater(() -> { dlg.setVisible(true); dlg.toFront(); });
            }
        }

        void set(int percent, String text) {
            final int clamped = Math.max(0, Math.min(100, percent));
            SwingUtilities.invokeLater(() -> {
                bar.setValue(clamped);
                pctLabel.setText(clamped + " %");
                statusLabel.setText(text);
            });
        }

        void close() {
            SwingUtilities.invokeLater(() -> {
                dlg.setVisible(false);
                dlg.dispose();
            });
        }
    }
}
