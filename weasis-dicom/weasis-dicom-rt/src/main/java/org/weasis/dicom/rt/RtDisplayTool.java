/*******************************************************************************
 * Copyright (c) 2010 Nicolas Roduit.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 ******************************************************************************/

package org.weasis.dicom.rt;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.JMVUtils;
import org.weasis.core.api.media.data.*;
import org.weasis.core.api.util.StringUtil;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.model.GraphicModel;
import org.weasis.core.ui.model.graphic.Graphic;
import org.weasis.core.ui.model.imp.XmlGraphicModel;
import org.weasis.core.ui.model.layer.LayerType;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.geometry.GeometryOfSlice;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.viewer2d.EventManager;

import bibliothek.gui.dock.common.CLocation;
import it.cnr.imaa.essi.lablib.gui.checkboxtree.CheckboxTree;
import it.cnr.imaa.essi.lablib.gui.checkboxtree.DefaultCheckboxTreeCellRenderer;
import it.cnr.imaa.essi.lablib.gui.checkboxtree.TreeCheckingEvent;
import it.cnr.imaa.essi.lablib.gui.checkboxtree.TreeCheckingModel;

public class RtDisplayTool extends PluginTool implements SeriesViewerListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtDisplayTool.class);

    public static final String BUTTON_NAME = "RT Tool";
    public static final int DockableWidth = javax.swing.UIManager.getLookAndFeel() != null
        ? javax.swing.UIManager.getLookAndFeel().getClass().getName().startsWith("org.pushingpixels") ? 190 : 205 : 205; //$NON-NLS-1$

    private final JScrollPane rootPane;
    private final JButton btnLoad;
    private final CheckboxTree tree;
    private boolean initPathSelection;
    private DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("rootNode", true); //$NON-NLS-1$
    private TreePath rootPath;
    private final JComboBox<RtSpecialElement> comboRtStructureSet;
    private final JComboBox<RtSpecialElement> comboRtPlan;
    private JPanel panel_foot;
    private final DefaultMutableTreeNode nodeStructures;
    private final DefaultMutableTreeNode nodeIsodoses;
    private RtSet rtSet;
    private final transient ItemListener structureChangeListener = e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
            updateTree((RtSpecialElement) e.getItem(), null);
        }
    };
    private final transient ItemListener planChangeListener = e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
            updateTree(null, (RtSpecialElement) e.getItem());
        }
    };

    public RtDisplayTool() {
        super(BUTTON_NAME, BUTTON_NAME, PluginTool.Type.TOOL, 30);
        this.rootPane = new JScrollPane();
        this.dockable.setTitleIcon(new ImageIcon(RtDisplayTool.class.getResource("/icon/16x16/rtDose.png"))); //$NON-NLS-1$
        this.setDockableWidth(DockableWidth);
        this.btnLoad = new JButton("Load RT");
        this.btnLoad.setToolTipText("Populate RT objects from loaded DICOM study");
        this.comboRtStructureSet = new JComboBox<>();
        this.comboRtStructureSet.setVisible(false);
        this.comboRtPlan = new JComboBox<>();
        this.comboRtPlan.setVisible(false);
        this.tree = new CheckboxTree();
        this.tree.setVisible(false);
        this.setLayout(new BorderLayout(0, 0));
        this.nodeStructures = new DefaultMutableTreeNode("Structures", true);
        this.nodeIsodoses = new DefaultMutableTreeNode("Isodoses", true);
        this.initTree();
    }

    public void actionPerformed(ActionEvent e) {
        if ("Load RT".equals(e.getActionCommand())) {

            // Reload RT case data objects for GUI
            this.rtSet.reloadRtCase();
            this.btnLoad.setEnabled(false);
            this.btnLoad.setToolTipText("RT objects from loaded DICOM study have been already created");
            this.comboRtStructureSet.setVisible(true);
            this.comboRtPlan.setVisible(true);
            this.tree.setVisible(true);

            // Update GUI
            ImageViewerPlugin<DicomImageElement> container = EventManager.getInstance().getSelectedView2dContainer();
            List<ViewCanvas<DicomImageElement>> views = null;
            if (container != null) {
                views = container.getImagePanels();
            }
            if (views != null) {
                for (ViewCanvas<DicomImageElement> v : views) {
                    updateCanvas(v);
                }
            }
        }
    }

    public void initTree() {
        this.tree.getCheckingModel().setCheckingMode(TreeCheckingModel.CheckingMode.SIMPLE);

        DefaultTreeModel model = new DefaultTreeModel(rootNode, false);
        tree.setModel(model);

        rootNode.add(nodeStructures);
        rootNode.add(nodeIsodoses);
        rootPath = new TreePath(rootNode.getPath());
        tree.addCheckingPath(rootPath);

        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);
        tree.setExpandsSelectedPaths(true);
        DefaultCheckboxTreeCellRenderer renderer = new DefaultCheckboxTreeCellRenderer();
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        renderer.setLeafIcon(null);
        tree.setCellRenderer(renderer);
        tree.addTreeCheckingListener(this::treeValueChanged);

        // GUI RT case selection
        JPanel panel = new JPanel();
        FlowLayout flowLayout = (FlowLayout) panel.getLayout();
        flowLayout.setAlignment(FlowLayout.LEFT);
        add(panel, BorderLayout.NORTH);
        panel.add(this.btnLoad);
        panel.add(this.comboRtStructureSet);
        panel.add(this.comboRtPlan);

        this.btnLoad.addActionListener(this::actionPerformed);

        expandTree(tree, rootNode);
        add(new JScrollPane(tree), BorderLayout.CENTER);

        panel_foot = new JPanel();
        panel_foot.setUI(new javax.swing.plaf.PanelUI() {
        });
        panel_foot.setOpaque(true);
        panel_foot.setBackground(JMVUtils.TREE_BACKROUND);
        add(panel_foot, BorderLayout.SOUTH);
    }

    private void initPathSelection(TreePath path, boolean selected) {
        if (selected) {
            tree.addCheckingPath(path);
        } else {
            tree.removeCheckingPath(path);
        }
    }

    private void treeValueChanged(TreeCheckingEvent e) {
        if (!initPathSelection) {
            TreePath path = e.getPath();
            boolean selected = e.isCheckedPath();
            Object selObject = path.getLastPathComponent();
            Object parent = null;
            if (path.getParentPath() != null) {
                parent = path.getParentPath().getLastPathComponent();
            }

            ImageViewerPlugin<DicomImageElement> container = EventManager.getInstance().getSelectedView2dContainer();
            List<ViewCanvas<DicomImageElement>> views = null;
            if (container != null) {
                views = container.getImagePanels();
            }
            if (views != null) {
                RtSet rt = rtSet;
                if (rt != null &&
                   ((selObject == nodeStructures || parent == nodeStructures) ||
                    (selObject == nodeIsodoses || parent == nodeIsodoses))) {
                    for (ViewCanvas<DicomImageElement> v : views) {
                        showGraphic(rt, getStructureSelection(), getIsoDoseSelection(), v);
                    }
                }
            }
        }
    }

    private List<StructureLayer> getStructureSelection() {
        ArrayList<StructureLayer> list = new ArrayList<>();
        if (tree.getCheckingModel().isPathChecked(new TreePath(nodeStructures.getPath()))) {
            TreePath[] paths = tree.getCheckingModel().getCheckingPaths();
            for (TreePath treePath : paths) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                if (node.getUserObject() instanceof StructureLayer) {
                    list.add((StructureLayer) node.getUserObject());
                }
            }
        }
        return list;
    }

    private List<IsoDoseLayer> getIsoDoseSelection() {
        ArrayList<IsoDoseLayer> list = new ArrayList<>();
        if (tree.getCheckingModel().isPathChecked(new TreePath(nodeIsodoses.getPath()))) {
            TreePath[] paths = tree.getCheckingModel().getCheckingPaths();
            for (TreePath treePath : paths) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                if (node.getUserObject() instanceof IsoDoseLayer) {
                    list.add((IsoDoseLayer) node.getUserObject());
                }
            }
        }
        return list;
    }

    private static boolean containsStructure(List<StructureLayer> list, Structure s) {
        for (StructureLayer structure : list) {
            if (structure.getStructure().getRoiNumber() == s.getRoiNumber() &&
                structure.getStructure().getRoiName().equals(s.getRoiName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIsoDose(List<IsoDoseLayer> list, IsoDose i) {
        for (IsoDoseLayer isoDoseLayer: list) {
            if (isoDoseLayer.getIsoDose().getLevel() == i.getLevel() &&
                isoDoseLayer.getIsoDose().getLabel().equals(i.getLabel())) {
                return true;
            }
        }
        return false;
    }

    private static void showGraphic(RtSet rt, List<StructureLayer> listStructure, List<IsoDoseLayer> listIsoDose, ViewCanvas<?> v) {
        if (rt != null) {
            ImageElement dicom = v.getImage();
            if (dicom instanceof DicomImageElement) {
                GeometryOfSlice geometry = ((DicomImageElement) dicom).getDispSliceGeometry();
                double z = geometry.getTLHC().getZ();

                // List of detected contours from RtSet
                List<Contour> contours =
                    rt.getContourMap().get(TagD.getTagValue(dicom, Tag.SOPInstanceUID, String.class));

                // List of detected plans from RtSet
                Plan plan = rt.getFirstPlan();
                Dose dose = null;
                if (plan != null) {
                    dose = plan.getFirstDose();
                }

                // Any RT layer is available
                if (contours != null || dose != null) {
                    GraphicModel modelList = (GraphicModel) dicom.getTagValue(TagW.PresentationModel);
                    // After getting a new image iterator, update the measurements
                    if (modelList == null) {
                        modelList = new XmlGraphicModel(dicom);
                        dicom.setTag(TagW.PresentationModel, modelList);
                    } else {
                        modelList.deleteByLayerType(LayerType.DICOM_RT);
                    }

                    // Iso dose contour layer
                    if (dose != null) {

                        for (IsoDoseLayer isoDoseLayer : dose.getIsoDoseSet().values()) {
                            IsoDose isoDose = isoDoseLayer.getIsoDose();

                            // Only selected
                            if (containsIsoDose(listIsoDose, isoDose)) {

                                // Contours for specific slice
                                ArrayList<Contour> isoContours = isoDose.getPlanes().get(z);
                                if (isoContours != null) {

                                    // Iso dose graphics
                                    for (Contour isoContour : isoContours) {
                                        Graphic graphic = isoContour.getGraphic(geometry);
                                        if (graphic != null) {

                                            graphic.setLineThickness((float) isoDose.getThickness());
                                            graphic.setPaint(isoDose.getColor());
                                            graphic.setLayerType(LayerType.DICOM_RT);
                                            graphic.setLayer(isoDoseLayer.getLayer());
                                            graphic.setFilled(true);

                                            for (PropertyChangeListener listener : modelList.getGraphicsListeners()) {
                                                graphic.addPropertyChangeListener(listener);
                                            }

                                            modelList.addGraphic(graphic);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Contours layer
                    if (contours != null) {
                        // Check which contours should be rendered
                        for (Contour c : contours) {
                            StructureLayer structLayer = (StructureLayer) c.getLayer();
                            Structure structure = structLayer.getStructure();
                            if (containsStructure(listStructure, structure)) {

//                                // If dose is loaded
//                                if (dose != null) {
//
//                                    // DVH refresh display
//                                    rt.getDvhChart().removeSeries(struct.getRoiName());
//                                    structureDvh.appendChart(struct.getRoiName(), rt.getDvhChart());
//                                    try {
//                                        BitmapEncoder.saveBitmap(rt.getDvhChart(), "./TEST-DVH", BitmapEncoder.BitmapFormat.PNG);
//                                    }
//                                    catch (Exception err) {
//
//                                    }
//                                }

                                // Structure graphics
                                Graphic graphic = c.getGraphic(geometry);
                                if (graphic != null) {

                                    graphic.setLineThickness((float) structure.getThickness());
                                    graphic.setPaint(structure.getColor());
                                    graphic.setLayerType(LayerType.DICOM_RT);
                                    graphic.setLayer(structLayer.getLayer());
                                    // External contour do not fill
                                    if (structure.getRtRoiInterpretedType().equals("EXTERNAL")) {
                                        graphic.setFilled(false);
                                    }
                                    // The other (organs, target volumes) should be filled
                                    else {
                                        graphic.setFilled(true);
                                    }

                                    for (PropertyChangeListener listener : modelList.getGraphicsListeners()) {
                                        graphic.addPropertyChangeListener(listener);
                                    }

                                    modelList.addGraphic(graphic);
                                }
                            }
                        }
                    }

                    v.getJComponent().repaint();
                }
            }
        }
    }

    public void updateCanvas(ViewCanvas<?> viewCanvas) {
        RtSet rt = rtSet;
        if (rt == null) {
            this.nodeStructures.removeAllChildren();
            this.nodeIsodoses.removeAllChildren();

            DefaultTreeModel model = new DefaultTreeModel(rootNode, false);
            tree.setModel(model);

            return;
        }

        comboRtStructureSet.removeItemListener(structureChangeListener);
        comboRtPlan.removeItemListener(planChangeListener);

        RtSpecialElement oldStructure = (RtSpecialElement) comboRtStructureSet.getSelectedItem();
        RtSpecialElement oldPlan = (RtSpecialElement) comboRtPlan.getSelectedItem();

        comboRtStructureSet.removeAllItems();
        comboRtPlan.removeAllItems();

        Set<RtSpecialElement> rtStructElements = rt.getStructures().keySet();
        Set<RtSpecialElement> rtPlanElements = rt.getPlans().keySet();

        rtStructElements.forEach(comboRtStructureSet::addItem);
        rtPlanElements.forEach(comboRtPlan::addItem);

        boolean update = !rtStructElements.contains(oldStructure);
        boolean update1 = !rtPlanElements.contains(oldPlan);

        if (update) {
            RtSpecialElement selectedStructure = rt.getFirstStructure();
            comboRtStructureSet.setSelectedItem(selectedStructure);
            updateTree(selectedStructure, oldPlan);
        } else {
            comboRtStructureSet.setSelectedItem(oldStructure);
        }

        if (update1 || !nodeIsodoses.children().hasMoreElements()) {
            RtSpecialElement selectedPlan = rt.getFirstPlanKey();
            comboRtPlan.setSelectedItem(selectedPlan);
            updateTree(oldStructure, selectedPlan);
        } else {
            comboRtPlan.setSelectedItem(oldPlan);
        }

        comboRtStructureSet.addItemListener(structureChangeListener);
        comboRtPlan.addItemListener(planChangeListener);

        // Update selected dose plane
//        ImageElement dicom = viewCanvas.getImage();
//        if (dicom instanceof DicomImageElement) {
//            GeometryOfSlice geometry = ((DicomImageElement) dicom).getDispSliceGeometry();
//
//            if (rt.getFirstDose() != null) {
//                rt.getDoseValueForPixel(247, 263, geometry.getTLHC().getZ());
//            }
//        }

        showGraphic(rt, getStructureSelection(), getIsoDoseSelection(), viewCanvas);
    }

    public void updateTree(RtSpecialElement selectedStructure, RtSpecialElement selectedPlan) {
        // Empty tree when no RtSet
        if (rtSet == null) {
            nodeStructures.removeAllChildren();
            nodeIsodoses.removeAllChildren();
            DefaultTreeModel model = new DefaultTreeModel(rootNode, false);
            tree.setModel(model);
            return;
        }

        initPathSelection = true;
        try {
            // Prepare root tree model
            DefaultTreeModel model = new DefaultTreeModel(rootNode, false);
            tree.setModel(model);

            // Prepare parent node for structures
            if (selectedStructure != null) {
                nodeStructures.removeAllChildren();
                Map<Integer, StructureLayer> structures = rtSet.getStructureSet(selectedStructure);
                if (structures != null) {
                    for (StructureLayer structureLayer : structures.values()) {
                        DefaultMutableTreeNode node = new DefaultMutableTreeNode(structureLayer, false);
                        this.nodeStructures.add(node);
                        initPathSelection(new TreePath(node.getPath()), false);
                    }
                }
                initPathSelection(new TreePath(nodeStructures.getPath()), true);
                for (Enumeration<?> children = nodeStructures.children(); children.hasMoreElements(); ) {
                    DefaultMutableTreeNode dtm = (DefaultMutableTreeNode) children.nextElement();
                    initPathSelection(new TreePath(dtm.getPath()), false);
                }
            }

            // Prepare parent node for isodoses
            if (selectedPlan != null) {
                nodeIsodoses.removeAllChildren();
                Dose planDose = rtSet.getPlan(selectedPlan).getFirstDose();
                if (planDose != null) {
                    Map<Integer, IsoDoseLayer> isodoses = planDose.getIsoDoseSet();
                    if (isodoses != null) {
                        for (IsoDoseLayer isoDoseLayer : isodoses.values()) {
                            DefaultMutableTreeNode node = new DefaultMutableTreeNode(isoDoseLayer, false);
                            this.nodeIsodoses.add(node);
                            initPathSelection(new TreePath(node.getPath()), false);
                        }
                    }
                    initPathSelection(new TreePath(nodeIsodoses.getPath()), true);
                    for (Enumeration<?> children = nodeIsodoses.children(); children.hasMoreElements(); ) {
                        DefaultMutableTreeNode dtm = (DefaultMutableTreeNode) children.nextElement();
                        initPathSelection(new TreePath(dtm.getPath()), false);
                    }
                }
            }

            // Expand
            expandTree(tree, rootNode);
        } finally {
            initPathSelection = false;
        }
    }

    public void initTreeValues(ViewCanvas<?> viewCanvas) {
        if (viewCanvas != null) {
            MediaSeries<?> dcmSeries = viewCanvas.getSeries();
            if (dcmSeries != null) {
                DicomModel dicomModel = (DicomModel) dcmSeries.getTagValue(TagW.ExplorerModel);
                if (dicomModel != null) {
                    MediaSeriesGroup patient = dicomModel.getParent(dcmSeries, DicomModel.patient);
                    if (patient != null) {
                        String frameOfReferenceUID = TagD.getTagValue(dcmSeries, Tag.FrameOfReferenceUID, String.class);
                        List<MediaElement> list = getRelatedSpecialElements(dicomModel, patient, frameOfReferenceUID);
                        if (!list.isEmpty() && (rtSet == null || !rtSet.getRtElements().equals(list))) {
                            this.rtSet = new RtSet(list);
                            this.btnLoad.setEnabled(true);
                        }
                        updateCanvas(viewCanvas);
                    }
                }
            }
        }
    }

    @Override
    public Component getToolComponent() {
        JViewport viewPort = rootPane.getViewport();
        if (viewPort == null) {
            viewPort = new JViewport();
            rootPane.setViewport(viewPort);
        }
        if (viewPort.getView() != this) {
            viewPort.setView(this);
        }
        return rootPane;
    }

    @Override
    public void changingViewContentEvent(SeriesViewerEvent event) {
        SeriesViewerEvent.EVENT e = event.getEventType();
        if (SeriesViewerEvent.EVENT.SELECT.equals(e) && event.getSeriesViewer() instanceof ImageViewerPlugin) {
            initTreeValues(((ImageViewerPlugin<?>) event.getSeriesViewer()).getSelectedImagePane());
        }
    }
    
    @Override
    protected void changeToolWindowAnchor(CLocation clocation) {
        // TODO Auto-generated method stub
    }

    private static void expandTree(JTree tree, DefaultMutableTreeNode start) {
        for (Enumeration children = start.children(); children.hasMoreElements();) {
            DefaultMutableTreeNode dtm = (DefaultMutableTreeNode) children.nextElement();
            if (!dtm.isLeaf()) {
                TreePath tp = new TreePath(dtm.getPath());
                tree.expandPath(tp);
                expandTree(tree, dtm);
            }
        }
    }

    private static List<MediaElement> getRelatedSpecialElements(DicomModel model, MediaSeriesGroup patient,
        String frameOfReferenceUID) {
        List<MediaElement> specialElementList = new ArrayList<>();
        if (StringUtil.hasText(frameOfReferenceUID)) {
            for (MediaSeriesGroup st : model.getChildren(patient)) {
                for (MediaSeriesGroup s : model.getChildren(st)) {
                    String frameUID = TagD.getTagValue(s, Tag.FrameOfReferenceUID, String.class);
                    String modality = TagD.getTagValue(s, Tag.Modality, String.class);
                    if (frameOfReferenceUID.equals(frameUID) || "RTSTRUCT".equals(modality)) {
                        List<RtSpecialElement> list = DicomModel.getSpecialElements(s, RtSpecialElement.class);
                        if (!list.isEmpty()) {
                            specialElementList.addAll(list);
                        }
                        if ("RTDOSE".equals(modality) && s instanceof DicomSeries) {
                            for (DicomImageElement media : ((DicomSeries) s).getMedias(null, null)) {
                                if ("RTDOSE".equals(TagD.getTagValue(media, Tag.Modality))) {
                                    specialElementList.add(media);
                                }
                            }
                        }
                        if ("CT".equals(modality) && s instanceof DicomSeries) {
                            for (DicomImageElement media : ((DicomSeries) s).getMedias(null, null)) {
                                if ("CT".equals(TagD.getTagValue(media, Tag.Modality))) {
                                    specialElementList.add(media);
                                }
                            }
                        }
                    }
                }
            }
        }
        return specialElementList;
    }
    
}
