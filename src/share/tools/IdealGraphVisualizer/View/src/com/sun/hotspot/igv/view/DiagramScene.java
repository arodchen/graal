/*
 * Copyright (c) 2008, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */
package com.sun.hotspot.igv.view;

import com.sun.hotspot.igv.view.widgets.BlockWidget;
import com.sun.hotspot.igv.view.widgets.LineWidget;
import com.sun.hotspot.igv.util.DoubleClickAction;
import com.sun.hotspot.igv.data.InputBlock;
import com.sun.hotspot.igv.data.InputNode;
import com.sun.hotspot.igv.graph.Connection;
import com.sun.hotspot.igv.graph.Diagram;
import com.sun.hotspot.igv.graph.Figure;
import com.sun.hotspot.igv.graph.OutputSlot;
import com.sun.hotspot.igv.hierarchicallayout.HierarchicalClusterLayoutManager;
import com.sun.hotspot.igv.hierarchicallayout.OldHierarchicalLayoutManager;
import com.sun.hotspot.igv.hierarchicallayout.HierarchicalLayoutManager;
import com.sun.hotspot.igv.view.widgets.FigureWidget;
import com.sun.hotspot.igv.layout.LayoutGraph;
import com.sun.hotspot.igv.data.services.Scheduler;
import com.sun.hotspot.igv.data.ChangedListener;
import com.sun.hotspot.igv.data.ControllableChangedListener;
import com.sun.hotspot.igv.data.Pair;
import com.sun.hotspot.igv.data.Properties;
import com.sun.hotspot.igv.graph.Block;
import com.sun.hotspot.igv.graph.InputSlot;
import com.sun.hotspot.igv.graph.Slot;
import com.sun.hotspot.igv.selectioncoordinator.SelectionCoordinator;
import com.sun.hotspot.igv.util.ColorIcon;
import com.sun.hotspot.igv.util.PropertiesSheet;
import com.sun.hotspot.igv.view.widgets.InputSlotWidget;
import com.sun.hotspot.igv.view.widgets.OutputSlotWidget;
import com.sun.hotspot.igv.view.widgets.SlotWidget;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.UndoableEditEvent;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import org.netbeans.api.visual.action.ActionFactory;
import org.netbeans.api.visual.action.PopupMenuProvider;
import org.netbeans.api.visual.action.RectangularSelectDecorator;
import org.netbeans.api.visual.action.RectangularSelectProvider;
import org.netbeans.api.visual.action.WidgetAction;
import org.netbeans.api.visual.animator.SceneAnimator;
import org.netbeans.api.visual.layout.LayoutFactory;
import org.netbeans.api.visual.model.ObjectScene;
import org.netbeans.api.visual.model.ObjectSceneEvent;
import org.netbeans.api.visual.model.ObjectSceneEventType;
import org.netbeans.api.visual.model.ObjectSceneListener;
import org.netbeans.api.visual.model.ObjectState;
import org.netbeans.api.visual.widget.LayerWidget;
import org.netbeans.api.visual.widget.Widget;
import org.openide.awt.UndoRedo;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;
import org.openide.nodes.Sheet;
import org.openide.util.Lookup;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;

/**
 *
 * @author Thomas Wuerthinger
 */
public class DiagramScene extends ObjectScene implements DiagramViewer {

    private WidgetAction hoverAction;
    private WidgetAction selectAction;
    private Lookup lookup;
    private InstanceContent content;
    private Action[] actions;
    private LayerWidget connectionLayer;
    private JScrollPane scrollPane;
    private UndoRedo.Manager undoRedoManager;
    private LayerWidget mainLayer;
    private LayerWidget blockLayer;
    private Widget topLeft;
    private Widget bottomRight;
    private DiagramViewModel model;
    private DiagramViewModel modelCopy;
    private WidgetAction zoomAction;
    private boolean rebuilding;
    
    /**
     * The alpha level of partially visible figures.
     */
    public static final float ALPHA = 0.4f;
    
    /**
     * The offset of the graph to the border of the window showing it.
     */
    public static final int BORDER_SIZE = 20;
    
    
    public static final int UNDOREDO_LIMIT = 100;
    public static final int SCROLL_UNIT_INCREMENT = 80;
    public static final int SCROLL_BLOCK_INCREMENT = 400;
    public static final float ZOOM_MAX_FACTOR = 3.0f;
    public static final float ZOOM_MIN_FACTOR = 0.0f;//0.15f;
    public static final float ZOOM_INCREMENT = 1.5f;
    public static final int SLOT_OFFSET = 6;
    public static final int ANIMATION_LIMIT = 40;
    
    private PopupMenuProvider popupMenuProvider = new PopupMenuProvider() {

        public JPopupMenu getPopupMenu(Widget widget, Point localLocation) {
            return DiagramScene.this.createPopupMenu();
        }
    };
    private RectangularSelectDecorator rectangularSelectDecorator = new RectangularSelectDecorator() {

        public Widget createSelectionWidget() {
            Widget widget = new Widget(DiagramScene.this);
            widget.setBorder(BorderFactory.createLineBorder(Color.black, 2));
            widget.setForeground(Color.red);
            return widget;
        }
    };

    @SuppressWarnings("unchecked")
    public <T> T getWidget(Object o) {
        Widget w = this.findWidget(o);
        return (T) w;
    }

    @SuppressWarnings("unchecked")
    public <T> T getWidget(Object o, Class<T> klass) {
        Widget w = this.findWidget(o);
        return (T) w;
    }

    private static boolean intersects(Set<? extends Object> s1, Set<? extends Object> s2) {
        for (Object o : s1) {
            if (s2.contains(o)) {
                return true;
            }
        }
        return false;
    }

    public void zoomOut() {
        double zoom = getZoomFactor();
        Point viewPosition = getScrollPane().getViewport().getViewPosition();
        double newZoom = zoom / DiagramScene.ZOOM_INCREMENT;
        if (newZoom > DiagramScene.ZOOM_MIN_FACTOR) {
            setZoomFactor(newZoom);
            validate();
            getScrollPane().getViewport().setViewPosition(new Point((int) (viewPosition.x / DiagramScene.ZOOM_INCREMENT), (int) (viewPosition.y / DiagramScene.ZOOM_INCREMENT)));
        }
    }

    public void zoomIn() {

        double zoom = getZoomFactor();
        Point viewPosition = getScrollPane().getViewport().getViewPosition();
        double newZoom = zoom * DiagramScene.ZOOM_INCREMENT;
        if (newZoom < DiagramScene.ZOOM_MAX_FACTOR) {
            setZoomFactor(newZoom);
            validate();
            getScrollPane().getViewport().setViewPosition(new Point((int) (viewPosition.x * DiagramScene.ZOOM_INCREMENT), (int) (viewPosition.y * DiagramScene.ZOOM_INCREMENT)));
        }
    }


    public void centerFigures(List<Figure> list) {

        boolean b = getUndoRedoEnabled();
        setUndoRedoEnabled(false);
        gotoFigures(list);
        setUndoRedoEnabled(b);
    }

    private Set<Object> getObjectsFromIdSet(Set<Object> set) {
        Set<Object> selectedObjects = new HashSet<Object>();
        for (Figure f : getModel().getDiagramToView().getFigures()) {
            if (intersects(f.getSource().getSourceNodesAsSet(), set)) {
                selectedObjects.add(f);
            }

            for (Slot s : f.getSlots()) {
                if (intersects(s.getSource().getSourceNodesAsSet(), set)) {
                    selectedObjects.add(s);
                }
            }
        }
        return selectedObjects;
    }
    private ControllableChangedListener<SelectionCoordinator> highlightedCoordinatorListener = new ControllableChangedListener<SelectionCoordinator>() {

        public void filteredChanged(SelectionCoordinator source) {
            DiagramScene.this.setHighlightedObjects(getObjectsFromIdSet(source.getHighlightedObjects()));
            DiagramScene.this.validate();
        }
    };
    private ControllableChangedListener<SelectionCoordinator> selectedCoordinatorListener = new ControllableChangedListener<SelectionCoordinator>() {

        public void filteredChanged(SelectionCoordinator source) {
            DiagramScene.this.gotoSelection(source.getSelectedObjects());
            DiagramScene.this.validate();
        }
    };

    private RectangularSelectProvider rectangularSelectProvider = new RectangularSelectProvider() {

        public void performSelection(Rectangle rectangle) {
            if (rectangle.width < 0) {
                rectangle.x += rectangle.width;
                rectangle.width *= -1;
            }

            if (rectangle.height < 0) {
                rectangle.y += rectangle.height;
                rectangle.height *= -1;
            }

            Set<Object> selectedObjects = new HashSet<Object>();
            for (Figure f : getModel().getDiagramToView().getFigures()) {
                FigureWidget w = getWidget(f);
                if (w != null) {
                    Rectangle r = new Rectangle(w.getBounds());
                    r.setLocation(w.getLocation());

                    if (r.intersects(rectangle)) {
                        selectedObjects.add(f);
                    }

                    for (Slot s : f.getSlots()) {
                        SlotWidget sw = getWidget(s);
                        Rectangle r2 = new Rectangle(sw.getBounds());
                        r2.setLocation(sw.convertLocalToScene(new Point(0, 0)));

                        if (r2.intersects(rectangle)) {
                            selectedObjects.add(s);
                        }
                    }
                } else {
                    assert false : "w should not be null here!";
                }
            }

            setSelectedObjects(selectedObjects);
        }
    };
    
    private MouseWheelListener mouseWheelListener = new MouseWheelListener() {

        public void mouseWheelMoved(MouseWheelEvent e) {
            DiagramScene.this.relayoutWithoutLayout(null);
        }
    };

    public Point getScrollPosition() {
        return getScrollPane().getViewport().getViewPosition();
    }

    public void setScrollPosition(Point p) {
        getScrollPane().getViewport().setViewPosition(p);
    }

    private JScrollPane createScrollPane() {
        JComponent comp = this.createView();
        comp.setDoubleBuffered(true);
        comp.setBackground(Color.WHITE);
        comp.setOpaque(true);
        this.setBackground(Color.WHITE);
        this.setOpaque(true);
        JScrollPane result = new JScrollPane(comp);
        result.setBackground(Color.WHITE);
        result.getVerticalScrollBar().setUnitIncrement(SCROLL_UNIT_INCREMENT);
        result.getVerticalScrollBar().setBlockIncrement(SCROLL_BLOCK_INCREMENT);
        result.getHorizontalScrollBar().setUnitIncrement(SCROLL_UNIT_INCREMENT);
        result.getHorizontalScrollBar().setBlockIncrement(SCROLL_BLOCK_INCREMENT);
        return result;
    }
    private ObjectSceneListener selectionChangedListener = new ObjectSceneListener() {

        public void objectAdded(ObjectSceneEvent arg0, Object arg1) {
        }

        public void objectRemoved(ObjectSceneEvent arg0, Object arg1) {
        }

        public void objectStateChanged(ObjectSceneEvent e, Object o, ObjectState oldState, ObjectState newState) {
        }

        public void selectionChanged(ObjectSceneEvent e, Set<Object> oldSet, Set<Object> newSet) {
            DiagramScene scene = (DiagramScene) e.getObjectScene();
            if (scene.isRebuilding()) {
                return;
            }

            content.set(newSet, null);

            Set<Integer> nodeSelection = new HashSet<Integer>();
            for (Object o : newSet) {
                if (o instanceof Properties.Provider) {
                    final Properties.Provider provider = (Properties.Provider) o;
                    AbstractNode node = new AbstractNode(Children.LEAF) {

                        @Override
                        protected Sheet createSheet() {
                            Sheet s = super.createSheet();
                            PropertiesSheet.initializeSheet(provider.getProperties(), s);
                            return s;
                        }
                    };
                    node.setDisplayName(provider.getProperties().get("name"));
                    content.add(node);
                }


                if (o instanceof Figure) {
                    nodeSelection.addAll(((Figure) o).getSource().getSourceNodesAsSet());
                } else if (o instanceof Slot) {
                    nodeSelection.addAll(((Slot) o).getSource().getSourceNodesAsSet());
                }
            }
            getModel().setSelectedNodes(nodeSelection);

            boolean b = selectedCoordinatorListener.isEnabled();
            selectedCoordinatorListener.setEnabled(false);
            SelectionCoordinator.getInstance().setSelectedObjects(nodeSelection);
            selectedCoordinatorListener.setEnabled(b);

        }

        public void highlightingChanged(ObjectSceneEvent e, Set<Object> oldSet, Set<Object> newSet) {
            Set<Integer> nodeHighlighting = new HashSet<Integer>();
            for (Object o : newSet) {
                if (o instanceof Figure) {
                    nodeHighlighting.addAll(((Figure) o).getSource().getSourceNodesAsSet());
                } else if (o instanceof Slot) {
                    nodeHighlighting.addAll(((Slot) o).getSource().getSourceNodesAsSet());
                }
            }
            boolean b = highlightedCoordinatorListener.isEnabled();
            highlightedCoordinatorListener.setEnabled(false);
            SelectionCoordinator.getInstance().setHighlightedObjects(nodeHighlighting);
            highlightedCoordinatorListener.setEnabled(true);
        }

        public void hoverChanged(ObjectSceneEvent e, Object oldObject, Object newObject) {
            Set<Object> newHighlightedObjects = new HashSet<Object>(DiagramScene.this.getHighlightedObjects());
            if (oldObject != null) {
                newHighlightedObjects.remove(oldObject);
            }
            if (newObject != null) {
                newHighlightedObjects.add(newObject);
            }
            DiagramScene.this.setHighlightedObjects(newHighlightedObjects);
        }

        public void focusChanged(ObjectSceneEvent arg0, Object arg1, Object arg2) {
        }
    };

    public DiagramScene(Action[] actions, DiagramViewModel model) {

        this.actions = actions;

        content = new InstanceContent();
        lookup = new AbstractLookup(content);

        this.setCheckClipping(true);

        this.getInputBindings().setZoomActionModifiers(0);

        scrollPane = createScrollPane();
        this.getActions().addAction(ActionFactory.createPanAction());
        hoverAction = createObjectHoverAction();
        selectAction = createSelectAction();
        this.getActions().addAction(selectAction);

        blockLayer = new LayerWidget(this);
        this.addChild(blockLayer);

        mainLayer = new LayerWidget(this);
        this.addChild(mainLayer);

        topLeft = new Widget(this);
        topLeft.setPreferredLocation(new Point(-BORDER_SIZE, -BORDER_SIZE));
        this.addChild(topLeft);

        bottomRight = new Widget(this);
        bottomRight.setPreferredLocation(new Point(-BORDER_SIZE, -BORDER_SIZE));
        this.addChild(bottomRight);

        connectionLayer = new LayerWidget(this);
        this.addChild(connectionLayer);

        LayerWidget selectionLayer = new LayerWidget(this);
        this.addChild(selectionLayer);

        this.setLayout(LayoutFactory.createAbsoluteLayout());

        zoomAction = ActionFactory.createMouseCenteredZoomAction(1.2);
        this.getActions().addAction(zoomAction);
        this.getView().addMouseWheelListener(mouseWheelListener);
        this.getActions().addAction(ActionFactory.createPopupMenuAction(popupMenuProvider));

        LayerWidget selectLayer = new LayerWidget(this);
        this.addChild(selectLayer);
        this.getActions().addAction(ActionFactory.createRectangularSelectAction(rectangularSelectDecorator, selectLayer, rectangularSelectProvider));

        boolean b = this.getUndoRedoEnabled();
        this.setUndoRedoEnabled(false);
        this.setNewModel(model);
        this.setUndoRedoEnabled(b);
        this.addObjectSceneListener(selectionChangedListener, ObjectSceneEventType.OBJECT_SELECTION_CHANGED, ObjectSceneEventType.OBJECT_HIGHLIGHTING_CHANGED, ObjectSceneEventType.OBJECT_HOVER_CHANGED);
    }

    public DiagramViewModel getModel() {
        return model;
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public Component getComponent() {
        return scrollPane;
    }
    
    public boolean isAllVisible() {
        return getModel().getHiddenNodes().size() == 0;
    }

    public Action createGotoAction(final Figure f) {
        final DiagramScene diagramScene = this;
        Action a = new AbstractAction() {

            public void actionPerformed(ActionEvent e) {
                diagramScene.gotoFigure(f);
            }
        };

        a.setEnabled(true);
        a.putValue(Action.SMALL_ICON, new ColorIcon(f.getColor()));
        String name = f.getLines()[0];

        name += " (";

        if (f.getCluster() != null) {
            name += "B" + f.getCluster().toString();
        }
        if (!this.getWidget(f, FigureWidget.class).isVisible()) {
            if (f.getCluster() != null) {
                name += ", ";
            }
            name += "hidden";
        }
        name += ")";
        a.putValue(Action.NAME, name);
        return a;
    }

    public void setNewModel(DiagramViewModel model) {
        assert this.model == null : "can set model only once!";
        this.model = model;
        this.modelCopy = null;

        model.getDiagramChangedEvent().addListener(fullChange);
        model.getViewPropertiesChangedEvent().addListener(fullChange);
        model.getViewChangedEvent().addListener(selectionChange);
        model.getHiddenNodesChangedEvent().addListener(hiddenNodesChange);
        update();
    }

    private void update() {
        mainLayer.removeChildren();
        blockLayer.removeChildren();
        
        rebuilding = true;

        Collection<Object> objects = new ArrayList<Object>(this.getObjects());
        for (Object o : objects) {
            this.removeObject(o);
        }

        Diagram d = getModel().getDiagramToView();

        if (d.getGraph().getBlocks().isEmpty()) {
            Scheduler s = Lookup.getDefault().lookup(Scheduler.class);
            d.getGraph().clearBlocks();
            s.schedule(d.getGraph());
            d.getGraph().ensureNodesInBlocks();
            d.updateBlocks();
        }

        for (Figure f : d.getFigures()) {
            FigureWidget w = new FigureWidget(f, hoverAction, selectAction, this, mainLayer);
            w.getActions().addAction(ActionFactory.createPopupMenuAction(w));
            w.getActions().addAction(selectAction);
            w.getActions().addAction(hoverAction);
            w.setVisible(false);

            this.addObject(f, w);

            for (InputSlot s : f.getInputSlots()) {
                SlotWidget sw = new InputSlotWidget(s, this, w, w);
                addObject(s, sw);
                sw.getActions().addAction(new DoubleClickAction(sw));
                sw.getActions().addAction(hoverAction);
                sw.getActions().addAction(selectAction);
            }

            for (OutputSlot s : f.getOutputSlots()) {
                SlotWidget sw = new OutputSlotWidget(s, this, w, w);
                addObject(s, sw);
                sw.getActions().addAction(new DoubleClickAction(sw));
                sw.getActions().addAction(hoverAction);
                sw.getActions().addAction(selectAction);
            }
        }

        if (getModel().getShowBlocks()) {
            for (InputBlock bn : d.getGraph().getBlocks()) {
                BlockWidget w = new BlockWidget(this, d, bn);
                w.setVisible(false);
                this.addObject(bn, w);
                blockLayer.addChild(w);
            }
        }
        
        rebuilding = false;
        this.smallUpdate(true);
    }
    
    public boolean isRebuilding() {
        return rebuilding;
    }

    private void smallUpdate(boolean relayout) {

        System.out.println("smallUpdate " + relayout);
        this.updateHiddenNodes(model.getHiddenNodes(), relayout);
        boolean b = this.getUndoRedoEnabled();
        this.setUndoRedoEnabled(false);
        this.setUndoRedoEnabled(b);
        this.validate();
    }

    private boolean isVisible(Connection c) {
        FigureWidget w1 = getWidget(c.getInputSlot().getFigure());
        FigureWidget w2 = getWidget(c.getOutputSlot().getFigure());

        if (w1.isVisible() && w2.isVisible()) {
            return true;
        }

        return false;
    }

    private void relayout(Set<Widget> oldVisibleWidgets) {
        System.out.println("relayout called with old visible widgets: " + oldVisibleWidgets);

        Diagram diagram = getModel().getDiagramToView();

        HashSet<Figure> figures = new HashSet<Figure>();

        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w.isVisible()) {
                figures.add(f);
            }
        }

        HashSet<Connection> edges = new HashSet<Connection>();

        for (Connection c : diagram.getConnections()) {
            if (isVisible(c)) {
                edges.add(c);
            }
        }

        if (getModel().getShowBlocks()) {
            HierarchicalClusterLayoutManager m = new HierarchicalClusterLayoutManager(OldHierarchicalLayoutManager.Combine.SAME_OUTPUTS);
            HierarchicalLayoutManager manager = new HierarchicalLayoutManager(HierarchicalLayoutManager.Combine.SAME_OUTPUTS);
            manager.setMaxLayerLength(9);
            manager.setMinLayerDifference(3);
            m.setManager(manager);
            m.setSubManager(new HierarchicalLayoutManager(HierarchicalLayoutManager.Combine.SAME_OUTPUTS));
            m.doLayout(new LayoutGraph(edges, figures));

        } else {
            HierarchicalLayoutManager manager = new HierarchicalLayoutManager(HierarchicalLayoutManager.Combine.SAME_OUTPUTS);
            manager.setMaxLayerLength(10);
            manager.doLayout(new LayoutGraph(edges, figures));
        }

        relayoutWithoutLayout(oldVisibleWidgets);
    }
    private Set<Pair<Point, Point>> lineCache = new HashSet<Pair<Point, Point>>();

    private void relayoutWithoutLayout(Set<Widget> oldVisibleWidgets) {

        System.out.println("relayout without layout with visible widgets: " + oldVisibleWidgets);

        Diagram diagram = getModel().getDiagramToView();

        int maxX = -BORDER_SIZE;
        int maxY = -BORDER_SIZE;
        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w.isVisible()) {
                Point p = f.getPosition();
                Dimension d = f.getSize();
                maxX = Math.max(maxX, p.x + d.width);
                maxY = Math.max(maxY, p.y + d.height);
            }
        }

        for (Connection c : diagram.getConnections()) {
            List<Point> points = c.getControlPoints();
            FigureWidget w1 = getWidget((Figure) c.getTo().getVertex());
            FigureWidget w2 = getWidget((Figure) c.getFrom().getVertex());
            if (w1.isVisible() && w2.isVisible()) {
                for (Point p : points) {
                    if (p != null) {
                        maxX = Math.max(maxX, p.x);
                        maxY = Math.max(maxY, p.y);
                    }
                }
            }
        }

        if (getModel().getShowBlocks()) {
            for (Block b : diagram.getBlocks()) {
                BlockWidget w = getWidget(b.getInputBlock());
                if (w != null && w.isVisible()) {
                    Rectangle r = b.getBounds();
                    maxX = Math.max(maxX, r.x + r.width);
                    maxY = Math.max(maxY, r.y + r.height);
                }
            }
        }

        bottomRight.setPreferredLocation(new Point(maxX + BORDER_SIZE, maxY + BORDER_SIZE));
        int offx = 0;
        int offy = 0;
        int curWidth = maxX + 2 * BORDER_SIZE;
        int curHeight = maxY + 2 * BORDER_SIZE;

        Rectangle bounds = this.getScrollPane().getBounds();
        bounds.width /= getZoomFactor();
        bounds.height /= getZoomFactor();
        if (curWidth < bounds.width) {
            offx = (bounds.width - curWidth) / 2;
        }

        if (curHeight < bounds.height) {
            offy = (bounds.height - curHeight) / 2;
        }

        final int offx2 = offx;
        final int offy2 = offy;

        SceneAnimator animator = this.getSceneAnimator();
        connectionLayer.removeChildren();
        int visibleFigureCount = 0;
        for (Figure f : diagram.getFigures()) {
            if (getWidget(f, FigureWidget.class).isVisible()) {
                visibleFigureCount++;
            }
        }


        Set<Pair<Point, Point>> lastLineCache = lineCache;
        lineCache = new HashSet<Pair<Point, Point>>();
        for (Figure f : diagram.getFigures()) {
            for (OutputSlot s : f.getOutputSlots()) {
                SceneAnimator anim = animator;
                if (visibleFigureCount > ANIMATION_LIMIT || oldVisibleWidgets == null) {
                    anim = null;
                }
                processOutputSlot(lastLineCache, s, s.getConnections(), 0, null, null, offx2, offy2, anim);
            }
        }

        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w.isVisible()) {
                Point p = f.getPosition();
                Point p2 = new Point(p.x + offx2, p.y + offy2);
                if ((visibleFigureCount <= ANIMATION_LIMIT && oldVisibleWidgets != null && oldVisibleWidgets.contains(w))) {
                    animator.animatePreferredLocation(w, p2);
                } else {
                    w.setPreferredLocation(p2);
                    animator.animatePreferredLocation(w, p2);
                }
            }
        }

        if (getModel().getShowBlocks()) {
            for (Block b : diagram.getBlocks()) {
                BlockWidget w = getWidget(b.getInputBlock());
                if (w != null && w.isVisible()) {
                    Point location = new Point(b.getBounds().x + offx2, b.getBounds().y + offy2);
                    Rectangle r = new Rectangle(location.x, location.y, b.getBounds().width, b.getBounds().height);
                    
                    if ((visibleFigureCount <= ANIMATION_LIMIT && oldVisibleWidgets != null && oldVisibleWidgets.contains(w))) {
                        animator.animatePreferredBounds(w, r);
                    } else {
                        w.setPreferredBounds(r);
                        animator.animatePreferredBounds(w, r);
                    }
                }
            }
        }

        this.validate();
    }
    private final Point specialNullPoint = new Point(Integer.MAX_VALUE, Integer.MAX_VALUE);

    private void processOutputSlot(Set<Pair<Point, Point>> lastLineCache, OutputSlot s, List<Connection> connections, int controlPointIndex, Point lastPoint, LineWidget predecessor, int offx, int offy, SceneAnimator animator) {
        Map<Point, List<Connection>> pointMap = new HashMap<Point, List<Connection>>(connections.size());

        for (Connection c : connections) {

            if (!isVisible(c)) {
                continue;
            }

            List<Point> controlPoints = c.getControlPoints();
            if (controlPointIndex >= controlPoints.size()) {
                continue;
            }

            Point cur = controlPoints.get(controlPointIndex);
            if (cur == null) {
                cur = specialNullPoint;
            } else if (controlPointIndex == 0 && !s.shouldShowName()) {
                cur = new Point(cur.x, cur.y - SLOT_OFFSET);
            } else if (controlPointIndex == controlPoints.size() - 1 && !c.getInputSlot().shouldShowName()) {
                cur = new Point(cur.x, cur.y + SLOT_OFFSET);
            }

            if (pointMap.containsKey(cur)) {
                pointMap.get(cur).add(c);
            } else {
                List<Connection> newList = new ArrayList<Connection>(2);
                newList.add(c);
                pointMap.put(cur, newList);
            }

        }

        for (Point p : pointMap.keySet()) {
            List<Connection> connectionList = pointMap.get(p);

            boolean isBold = false;
            boolean isDashed = true;

            for (Connection c : connectionList) {

                if (c.getStyle() == Connection.ConnectionStyle.BOLD) {
                    isBold = true;
                }

                if (c.getStyle() != Connection.ConnectionStyle.DASHED) {
                    isDashed = false;
                }
            }

            LineWidget newPredecessor = predecessor;
            if (p == specialNullPoint) {
            } else if (lastPoint == specialNullPoint) {
            } else if (lastPoint != null) {
                Point p1 = new Point(lastPoint.x + offx, lastPoint.y + offy);
                Point p2 = new Point(p.x + offx, p.y + offy);

                Pair<Point, Point> curPair = new Pair<Point, Point>(p1, p2);
                SceneAnimator curAnimator = animator;
                if (lastLineCache.contains(curPair)) {
                    curAnimator = null;
                }
                LineWidget w = new LineWidget(this, s, connectionList, p1, p2, predecessor, curAnimator, isBold, isDashed);
                lineCache.add(curPair);

                newPredecessor = w;
                connectionLayer.addChild(w);
                this.addObject(new ConnectionSet(connectionList), w);
                w.getActions().addAction(hoverAction);
            }

            processOutputSlot(lastLineCache, s, connectionList, controlPointIndex + 1, p, newPredecessor, offx, offy, animator);
        }
    }

    private class ConnectionSet {

        private Set<Connection> connections;

        public ConnectionSet(Collection<Connection> connections) {
            connections = new HashSet<Connection>(connections);
        }

        public Set<Connection> getConnectionSet() {
            return Collections.unmodifiableSet(connections);
        }
    }

    @Override
    public Lookup getLookup() {
        return lookup;
    }

    public void initialize() {
        Figure f = getModel().getDiagramToView().getRootFigure();
        if (f != null) {
            setUndoRedoEnabled(false);
            gotoFigure(f);
            setUndoRedoEnabled(true);
        }
    }

    public void gotoFigures(final List<Figure> figures) {
        Rectangle overall = null;
        getModel().showFigures(figures);
        for (Figure f : figures) {

            FigureWidget fw = getWidget(f);
            if (fw != null) {
                Rectangle r = fw.getBounds();
                Point p = fw.getLocation();
                Rectangle r2 = new Rectangle(p.x, p.y, r.width, r.height);

                if (overall == null) {
                    overall = r2;
                } else {
                    overall = overall.union(r2);
                }
            }
        }
        if (overall != null) {
            centerRectangle(overall);
        }
    }

    private Set<Object> idSetToObjectSet(Set<Object> ids) {

        Set<Object> result = new HashSet<Object>();
        for (Figure f : getModel().getDiagramToView().getFigures()) {
            if (DiagramScene.doesIntersect(f.getSource().getSourceNodesAsSet(), ids)) {
                result.add(f);
            }

            for (Slot s : f.getSlots()) {
                if (DiagramScene.doesIntersect(s.getSource().getSourceNodesAsSet(), ids)) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    public void gotoSelection(Set<Object> ids) {

        Rectangle overall = null;
        Set<Integer> hiddenNodes = new HashSet<Integer>(this.getModel().getHiddenNodes());
        hiddenNodes.removeAll(ids);
        this.getModel().showNot(hiddenNodes);

        Set<Object> objects = idSetToObjectSet(ids);
        for (Object o : objects) {

            Widget w = getWidget(o);
            if (w != null) {
                Rectangle r = w.getBounds();
                Point p = w.convertLocalToScene(new Point(0, 0));

                Rectangle r2 = new Rectangle(p.x, p.y, r.width, r.height);

                if (overall == null) {
                    overall = r2;
                } else {
                    overall = overall.union(r2);
                }
            }
        }
        if (overall != null) {
            centerRectangle(overall);
        }

        setSelectedObjects(objects);
    }

    private Point calcCenter(Rectangle r) {

        Point center = new Point((int) r.getCenterX(), (int) r.getCenterY());
        center.x -= getScrollPane().getViewport().getViewRect().width / 2;
        center.y -= getScrollPane().getViewport().getViewRect().height / 2;

        // Ensure to be within area
        center.x = Math.max(0, center.x);
        center.x = Math.min(getScrollPane().getViewport().getViewSize().width - getScrollPane().getViewport().getViewRect().width, center.x);
        center.y = Math.max(0, center.y);
        center.y = Math.min(getScrollPane().getViewport().getViewSize().height - getScrollPane().getViewport().getViewRect().height, center.y);

        return center;
    }

    private void centerRectangle(Rectangle r) {

        if (getScrollPane().getViewport().getViewRect().width == 0 || getScrollPane().getViewport().getViewRect().height == 0) {
            return;
        }

        Rectangle r2 = new Rectangle(r.x, r.y, r.width, r.height);
        r2 = convertSceneToView(r2);

        double factorX = (double) r2.width / (double) getScrollPane().getViewport().getViewRect().width;
        double factorY = (double) r2.height / (double) getScrollPane().getViewport().getViewRect().height;
        double factor = Math.max(factorX, factorY);
        if (factor >= 1.0) {
            Point p = getScrollPane().getViewport().getViewPosition();
            setZoomFactor(getZoomFactor() / factor);
            r2.x /= factor;
            r2.y /= factor;
            r2.width /= factor;
            r2.height /= factor;
            getScrollPane().getViewport().setViewPosition(calcCenter(r2));
        } else {
            getScrollPane().getViewport().setViewPosition(calcCenter(r2));
        }
    }

    public void setSelection(Collection<Figure> list) {
        super.setSelectedObjects(new HashSet<Figure>(list));
    }

    private UndoRedo.Manager getUndoRedoManager() {
        if (undoRedoManager == null) {
            undoRedoManager = new UndoRedo.Manager();
            undoRedoManager.setLimit(UNDOREDO_LIMIT);
        }

        return undoRedoManager;
    }

    public UndoRedo getUndoRedo() {
        return getUndoRedoManager();
    }

    private boolean isVisible(Figure f) {
        for (Integer n : f.getSource().getSourceNodesAsSet()) {
            if (getModel().getHiddenNodes().contains(n)) {
                return false;
            }
        }
        return true;
    }

    public static boolean doesIntersect(Set s1, Set s2) {
        if (s1.size() > s2.size()) {
            Set tmp = s1;
            s1 = s2;
            s2 = tmp;
        }

        for (Object o : s1) {
            if (s2.contains(o)) {
                return true;
            }
        }

        return false;
    }

    public void componentHidden() {
        SelectionCoordinator.getInstance().getHighlightedChangedEvent().removeListener(highlightedCoordinatorListener);
        SelectionCoordinator.getInstance().getSelectedChangedEvent().removeListener(selectedCoordinatorListener);
    }

    public void componentShowing() {
        SelectionCoordinator.getInstance().getHighlightedChangedEvent().addListener(highlightedCoordinatorListener);
        SelectionCoordinator.getInstance().getSelectedChangedEvent().addListener(selectedCoordinatorListener);
    }

    private void updateHiddenNodes(Set<Integer> newHiddenNodes, boolean doRelayout) {

        System.out.println("newHiddenNodes: " + newHiddenNodes);
        Set<InputBlock> visibleBlocks = new HashSet<InputBlock>();

        Diagram diagram = getModel().getDiagramToView();
        assert diagram != null;

        Set<Widget> oldVisibleWidgets = new HashSet<Widget>();

        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w != null && w.isVisible()) {
                oldVisibleWidgets.add(w);
            }
        }

        if (getModel().getShowBlocks()) {
            for (InputBlock b : diagram.getGraph().getBlocks()) {
                BlockWidget w = getWidget(b);
                if (w.isVisible()) {
                    oldVisibleWidgets.add(w);
                }
            }
        }

        for (Figure f : diagram.getFigures()) {
            boolean hiddenAfter = doesIntersect(f.getSource().getSourceNodesAsSet(), newHiddenNodes);

            FigureWidget w = getWidget(f);
            w.setBoundary(false);
            if (!hiddenAfter) {
                // Figure is shown
                w.setVisible(true);
                for (InputNode n : f.getSource().getSourceNodes()) {
                    visibleBlocks.add(diagram.getGraph().getBlock(n));
                }
            } else {
                // Figure is hidden
                w.setVisible(false);
            }
        }

        if (getModel().getShowNodeHull()) {
            List<FigureWidget> boundaries = new ArrayList<FigureWidget>();
            for (Figure f : diagram.getFigures()) {
                FigureWidget w = getWidget(f);
                if (!w.isVisible()) {
                    Set<Figure> set = new HashSet<Figure>(f.getPredecessorSet());
                    set.addAll(f.getSuccessorSet());

                    boolean b = false;
                    for (Figure neighbor : set) {
                        FigureWidget neighborWidget = getWidget(neighbor);
                        if (neighborWidget.isVisible()) {
                            b = true;
                            break;
                        }
                    }

                    if (b) {
                        w.setBoundary(true);
                        for (InputNode n : f.getSource().getSourceNodes()) {
                            visibleBlocks.add(diagram.getGraph().getBlock(n));
                        }
                        boundaries.add(w);
                    }
                }
            }

            for (FigureWidget w : boundaries) {
                if (w.isBoundary()) {
                    w.setVisible(true);
                }
            }
        }

        if (getModel().getShowBlocks()) {
            for (InputBlock b : diagram.getGraph().getBlocks()) {

                boolean visibleAfter = visibleBlocks.contains(b);

                BlockWidget w = getWidget(b);
                if (visibleAfter) {
                    // Block must be shown
                    w.setVisible(true);
                } else {
                    // Block must be hidden
                    w.setVisible(false);
                }
            }
        }

        if (doRelayout) {
            relayout(oldVisibleWidgets);
        }
        this.validate();
        addUndo();
    }

    private void showFigure(Figure f) {
        HashSet<Integer> newHiddenNodes = new HashSet<Integer>(getModel().getHiddenNodes());
        newHiddenNodes.removeAll(f.getSource().getSourceNodesAsSet());
        updateHiddenNodes(newHiddenNodes, true);
    }

    public void show(final Figure f) {
        showFigure(f);
    }

    public void setSelectedObjects(Object... args) {
        Set<Object> set = new HashSet<Object>();
        for (Object o : args) {
            set.add(o);
        }
        super.setSelectedObjects(set);
    }

    private void centerWidget(Widget w) {
        Rectangle r = w.getBounds();
        Point p = w.getLocation();
        centerRectangle(new Rectangle(p.x, p.y, r.width, r.height));

    }

    public void gotoFigure(final Figure f) {

        if (!isVisible(f)) {
            showFigure(f);
        }

        FigureWidget fw = getWidget(f);
        if (fw != null) {
            centerWidget(fw);
            getModel().setSelectedNodes(f.getSource().getSourceNodesAsSet());
        }
    }

    public JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (Action a : actions) {
            if (a == null) {
                menu.addSeparator();
            } else {
                menu.add(a);
            }
        }
        return menu;
    }

    private static class DiagramUndoRedo extends AbstractUndoableEdit implements ChangedListener<DiagramViewModel> {

        private DiagramViewModel oldModel;
        private DiagramViewModel newModel;
        private Point oldScrollPosition;
        private DiagramScene scene;

        public DiagramUndoRedo(DiagramScene scene, Point oldScrollPosition, DiagramViewModel oldModel, DiagramViewModel newModel) {
            assert oldModel != null;
            assert newModel != null;
            this.oldModel = oldModel;
            this.newModel = newModel;
            this.scene = scene;
            this.oldScrollPosition = oldScrollPosition;
        }

        @Override
        public void redo() throws CannotRedoException {
            super.redo();
            boolean b = scene.getUndoRedoEnabled();
            scene.setUndoRedoEnabled(false);
            scene.getModel().getViewChangedEvent().addListener(this);
            scene.getModel().setData(newModel);
            scene.getModel().getViewChangedEvent().removeListener(this);
            scene.setUndoRedoEnabled(b);
        }

        @Override
        public void undo() throws CannotUndoException {
            super.undo();
            boolean b = scene.getUndoRedoEnabled();
            scene.setUndoRedoEnabled(false);
            scene.getModel().getViewChangedEvent().addListener(this);
            scene.getModel().setData(oldModel);
            scene.getModel().getViewChangedEvent().removeListener(this);

            SwingUtilities.invokeLater(new Runnable() {

                public void run() {
                    scene.setScrollPosition(oldScrollPosition);
                }
            });

            scene.setUndoRedoEnabled(b);
        }

        public void changed(DiagramViewModel source) {
            scene.getModel().getViewChangedEvent().removeListener(this);
            if (oldModel.getHiddenNodes().equals(newModel.getHiddenNodes())) {
                scene.smallUpdate(false);
            } else {
                scene.smallUpdate(true);
            }
        }
    }
    private boolean undoRedoEnabled = true;

    public void setUndoRedoEnabled(boolean b) {
        this.undoRedoEnabled = b;
    }

    public boolean getUndoRedoEnabled() {
        return undoRedoEnabled;
    }

    private final ChangedListener<DiagramViewModel> fullChange = new ChangedListener<DiagramViewModel>() {
        public void changed(DiagramViewModel source) {
            assert source == model : "Receive only changed event from current model!";
            assert source != null;
            update();
        }
    };

    private final ChangedListener<DiagramViewModel> hiddenNodesChange = new ChangedListener<DiagramViewModel>() {
        public void changed(DiagramViewModel source) {
            assert source == model : "Receive only changed event from current model!";
            assert source != null;
            smallUpdate(true);
        }
    };

    private final ChangedListener<DiagramViewModel> selectionChange = new ChangedListener<DiagramViewModel>() {
        public void changed(DiagramViewModel source) {
            assert source == model : "Receive only changed event from current model!";
            assert source != null;
            smallUpdate(false);
        }
    };


    private void addUndo() {

        DiagramViewModel newModelCopy = model.copy();

        if (undoRedoEnabled) {
            this.getUndoRedoManager().undoableEditHappened(new UndoableEditEvent(this, new DiagramUndoRedo(this, this.getScrollPosition(), modelCopy, newModelCopy)));
        }

        this.modelCopy = newModelCopy;
    }
}
