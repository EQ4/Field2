package fieldbox;

import field.app.RunLoop;
import field.app.ThreadSync;
import field.dynalink.linker.GuardingDynamicLinker;
import field.graphics.*;
import field.utility.AutoPersist;
import field.utility.Dict;
import field.utility.Log;
import fieldagent.Main;
import fieldbox.boxes.*;
import fieldbox.boxes.plugins.*;
import fieldbox.boxes.plugins.Image;
import fieldbox.execution.Execution;
import fieldbox.io.IO;
import fieldbox.ui.Compositor;
import fieldbox.ui.FieldBoxWindow;
import fieldcef.plugins.GlassBrowser;
import fieldcef.plugins.OutputBox;
import fieldcef.plugins.TextEditor;
import fieldcef.plugins.TextEditor_boxBrowser;
import fielded.ServerSupport;
import fielded.boxbrowser.BoxBrowser;
import fielded.plugins.Out;
import fieldlinker.Linker;
import fieldnashorn.Nashorn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.IOException;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;

/**
 * This Opens a document, loading a Window and a standard assortment of plugins into the top of a Box graph and the document into the "bottom" of the
 * Box graph.
 * <p>
 * A Plugin is simply something that's initialized: Constructor(boxes.root()).connect(boxes.root()) we can take these from a classname and can
 * optionally initialize them connected to something else
 */
public class Open {

	static public final Dict.Prop<String> fieldFilename = new Dict.Prop<>("fieldFilename").toCannon().type().doc("the name of the field sheet that we are currently in");

	private FieldBoxWindow window;
	private final Boxes boxes;
	private final Drawing drawing;
	private final FLineDrawing frameDrawing;
	private final Mouse mouse;
	private final FrameManipulation frameManipulation;
	private final TextDrawing textDrawing;
	private final FLineInteraction interaction;

	private final MarkingMenus markingMenus;
	private final String filename;
	private final Keyboard keyboard;
	private final Drops drops;
	private PluginList pluginList;
	private Map<String, List<Object>> plugins;
	private Nashorn javascript;

	private int sizeX = AutoPersist.persist("window_sizeX", () -> 1000, x -> Math.min(1920*2, Math.max(100, x)), (x) -> window == null ? x : (int) window.getBounds().w);
	private int sizeY = AutoPersist.persist("window_sizeY", () -> 800, x -> Math.min(1920*2, Math.max(100, x)), (x) -> window == null ? x : (int) window.getBounds().h);
	private int atX = AutoPersist.persist("window_atX", () -> 0, x -> x, (x) -> window == null ? x : (int) window.getBounds().x);
	private int atY = AutoPersist.persist("window_atY", () -> 0, x -> x, (x) -> window == null ? x : (int) window.getBounds().y);

	public Open(String filename) {
		System.err.println(":HI:");

		Log.log("startup", () -> "trouble is :" + GuardingDynamicLinker.class + " " + Linker.class);
		Log.log("startup", () -> "trouble is :" + GuardingDynamicLinker.class.getClassLoader() + " " + Linker.class.getClassLoader());

		DefaultMenus.safeToSave = false;

		this.filename = filename;
		Log.log("startup", () -> " -- Initializing window -- ");

		try {
			pluginList = new PluginList();
			plugins = pluginList.read(System.getProperty("user.home") + "/.field/plugins.edn", true);

			if (plugins != null) pluginList.interpretClassPathAndOptions(plugins);

			FieldBox.fieldBox.io.setPluginList(pluginList);

		} catch (IOException e) {
			e.printStackTrace();
			pluginList = null;
		}

		System.out.println(" window dimensions are :"+atX+" "+atY+" "+sizeX+" "+sizeY);

		window = new FieldBoxWindow(atX, atY, sizeX, sizeY, filename);

		window.scene
		      .attach(-5, this::defaultGLPreambleBackground);
		window.mainLayer()
		      .attach(-5, this::defaultGLPreamble);

		boxes = new Boxes();
		boxes.root().properties.put(Boxes.window, window);

		window.getCompositor()
		      .newLayer("glass");
		window.getCompositor()
		      .getLayer("glass")
		      .getScene()
		      .attach(-5, this::defaultGLPreambleTransparent);

		window.getCompositor()
		      .newLayer("glass2");
		window.getCompositor()
		      .getLayer("glass2")
		      .getScene()
		      .attach(-5, this::defaultGLPreambleTransparent);

		Watches watches = new Watches();
		watches.connect(boxes.root());

		drawing = new Drawing();
		// add the default layer to the box graph
		drawing.install(boxes.root());
		// add the glass layer to the box graph
		drawing.install(boxes.root(), "glass");
		drawing.install(boxes.root(), "glass2");
		// connect drawing to the box graph
		drawing.connect(boxes.root());

		textDrawing = new TextDrawing();
		// add the text default layer to the box graph
		textDrawing.install(boxes.root());
		// add the text glass layer to the box graph
		textDrawing.install(boxes.root(), "glass");
		textDrawing.install(boxes.root(), "glass2");
		// connect text drawing to the box graph
		textDrawing.connect(boxes.root());

		frameDrawing = (FLineDrawing) new FLineDrawing(boxes.root()).connect(boxes.root());

		mouse = new Mouse();
		window.addMouseHandler(state -> {
			mouse.dispatch(boxes.root(), state);
			return true;
		});

		keyboard = new Keyboard();
		window.addKeyboardHandler(state -> {
			keyboard.dispatch(boxes.root(), state);
			return true;
		});

		drops = new Drops();
		window.addDropHandler(state -> {
			drops.dispatch(boxes.root(), state);
			return true;
		});


		boxes.root().properties.put(fieldFilename, filename);

		// MarkingMenus must come before FrameManipulation, so FrameManipulation can handle selection state modification before MarkingMenus run
		markingMenus = (MarkingMenus) new MarkingMenus(boxes.root()).connect(boxes.root());

		frameManipulation = (FrameManipulation) new FrameManipulation(boxes.root()).connect(boxes.root());

		// Interaction must come before frameManipulation, otherwise all those drags with FLines become marquees on the canvas
		interaction = (FLineInteraction) new FLineInteraction(boxes.root()).connect(boxes.root());

		// here are some examples of plugins
		new Delete(boxes.root()).connect(boxes.root());

		new Topology(boxes.root()).connect(boxes.root());

		new Dispatch(boxes.root()).connect(boxes.root());

		new Chorder(boxes.root()).connect(boxes.root());

		new DefaultMenus(boxes.root(), filename).connect(boxes.root());

		new IsExecuting(boxes.root()).connect(boxes.root());

		new Rename(boxes.root()).connect(boxes.root());

		new Scrolling(boxes.root()).connect(boxes.root());

		new GraphicsSupport(boxes.root()).connect(boxes.root());

		new BlankCanvas(boxes.root()).connect(boxes.root());

		new DragFilesToCanvas(boxes.root()).connect(boxes.root());

		new Reload(boxes.root()).connect(boxes.root());

		new PluginsPlugin(boxes.root()).connect(boxes.root());

		new FrameConstraints(boxes.root()).connect(boxes.root());

		new Alignment(boxes.root()).connect(boxes.root());

//		new StatusBar(boxes.root()).connect(boxes.root());

		new HotkeyMenus(boxes.root(), null).connect(boxes.root());

//		new Typing(boxes.root()).connect(boxes.root());

		new RunCommand(boxes.root()).connect(boxes.root());

		new Auto(boxes.root()).connect(boxes.root());

		new FrameChangedHash(boxes.root()).connect(boxes.root());

		new Directionality(boxes.root()).connect(boxes.root());

		new Handles(boxes.root()).connect(boxes.root());

		new Create(boxes.root()).connect(boxes.root());

		new DragToCopy(boxes.root()).connect(boxes.root());

		new Pseudo(boxes.root()).connect(boxes.root());

		new Taps(boxes.root()).connect(boxes.root());

		new Image(boxes.root()).connect(boxes.root());

		new TextEditor(boxes.root()).connect(boxes.root());
		new GlassBrowser(boxes.root()).connect(boxes.root());
		new OutputBox(boxes.root()).connect(boxes.root());

		new BoxBrowser(boxes.root()).connect(boxes.root());
		new TextEditor_boxBrowser(boxes.root()).connect(boxes.root());

		new Templates(boxes.root()).connect(boxes.root());

		new Notifications(boxes.root()).connect(boxes.root());

		new KeyboardFocus(boxes.root()).connect(boxes.root());

		new RevealInFinder(boxes.root()).connect(boxes.root());

		new Channels(boxes.root()).connect(boxes.root());

		new MissingStream(boxes.root()).connect(boxes.root());

		new KeyboardShortcuts(boxes.root()).connect(boxes.root());

		new PresentationMode(boxes.root()).connect(boxes.root());

		new Increment(boxes.root()).connect(boxes.root());

		new Out(boxes.root()).connect(boxes.root());

		new Group(boxes.root()).connect(boxes.root());


		if (ThreadSync.enabled)
			new ThreadSyncFeedback(boxes.root()).connect(boxes.root());

		FileBrowser fb = new FileBrowser(boxes.root());
		fb.connect(boxes.root());

		/* cascade two blurs, a vertical and a horizontal together from the glass layer onto the base layer */
		Compositor.Layer lx = window.getCompositor()
					    .newLayer("__main__blurx", 0, 8);
		Compositor.Layer ly = window.getCompositor()
					    .newLayer("__main__blury", 1, 8);
		Compositor.Layer composited = window.getCompositor()
						    .newLayer("__main__composited", 0);

		composited.getScene()
			  .attach(-10, this::defaultGLPreamble);

		window.getCompositor()
		      .getMainLayer()
		      .blurYInto(5, lx.getScene());
		lx.blurXInto(5, ly.getScene());

		lx.addDependancy(window.getCompositor().getMainLayer());
		ly.addDependancy(lx);

		window.getCompositor()
		      .getMainLayer()
		      .drawInto(window.scene);

		window.getCompositor()
		      .getLayer("glass")
		      .compositeWith(ly, composited.getScene());

		composited.addDependancy(window.getCompositor().getLayer("glass"));
		composited.addDependancy(ly);

		window.getCompositor()
		      .getLayer("glass")
		      .compositeWith(ly, window.scene);

		lx = window.getCompositor()
			   .newLayer("__main__gblurx", 0, 8);
		ly = window.getCompositor()
			   .newLayer("__main__gblury", 1, 8);


		composited.blurYInto(5, lx.getScene());
		lx.blurXInto(5, ly.getScene());

		lx.addDependancy(composited);
		ly.addDependancy(lx);

		window.getCompositor()
		      .getLayer("glass2")
		      .compositeWith(ly, window.scene);


		/* reports on how much data we're sending to OpenGL and how much the MeshBuilder caching system is getting us. This is useful for noticing when we're repainting excessively or our cache is suddenly blown completely */
		RunLoop.main.getLoop()
			    .attach(10, Scene.strobe((i) -> {
				    if (MeshBuilder.cacheHits + MeshBuilder.cacheMisses_internalHash + MeshBuilder.cacheMisses_cursor + MeshBuilder.cacheMisses_externalHash > 0) {
					    Log.println("graphics.stats", " meshbuilder cache h" + MeshBuilder.cacheHits + " | mc" + MeshBuilder.cacheMisses_cursor + " / meh" + MeshBuilder.cacheMisses_externalHash + " / mih" + MeshBuilder.cacheMisses_internalHash+" / mto" + MeshBuilder.cacheMisses_tooOld+" | tex"+Texture.bytesUploaded);
					    MeshBuilder.cacheHits = 0;
					    MeshBuilder.cacheMisses_cursor = 0;
					    MeshBuilder.cacheMisses_externalHash = 0;
					    MeshBuilder.cacheMisses_internalHash = 0;
					    MeshBuilder.cacheMisses_tooOld = 0;
					    Texture.bytesUploaded = 0;
				    }
				    if (SimpleArrayBuffer.uploadBytes > 0) {
					    Log.println("graphics.stats", " uploaded " + SimpleArrayBuffer.uploadBytes + " bytes to OpenGL");
					    SimpleArrayBuffer.uploadBytes = 0;
				    }
			    }, 600));

		//initializes window mgmt for linux
//		if (Main.os == Main.OS.linux) new LinuxWindowTricks(boxes.root());
		//initializes window mgmt for osx
//		if (Main.os == Main.OS.mac) new OSXWindowTricks(boxes.root());


		// add Javascript runtime as base execution layer
		javascript = new Nashorn();
		Execution execution = new Execution((box, prop) -> (prop.equals(Execution.code) ? javascript.apply(box, prop) : null));
		execution.connect(boxes.root());

		new ServerSupport(boxes);//.openEditor();

		// add a red line time slider to the sheet (this isn't saved with the document, so we'll add it each time
		TimeSlider ts = new TimeSlider();
		boxes.root()
		     .connect(ts);

		boxes.root().properties.put(TimeSlider.time, ts);

		// actually open the document that's stored on disk
		doOpen(boxes.root(), filename);

		// call loaded on everything above root
		Log.log("startup", ()->"calling .loaded on plugins");
		boxes.root().breadthFirst(boxes.root().upwards()).filter(x -> x instanceof IO.Loaded).forEach(
			    x -> ((IO.Loaded) x).loaded());

		Log.log("startup", ()->" -- FieldBox finished initializing, loading plugins ... -- ");

		// initialize the plugins

		if (pluginList != null) pluginList.interpretPlugins(plugins, boxes.root());

		Log.log("startup", ()->" -- FieldBox plugins finished, entering animation loop -- ");

		// start the runloop
		boxes.start();



		DefaultMenus.safeToSave = true;

	}

	static public Set<Box> doOpen(Box root, String filename) {
		Map<String, Box> special = new LinkedHashMap<>();
		special.put(">>root<<", root);

		Set<Box> created = new LinkedHashSet<Box>();
		IO.Document doc = FieldBox.fieldBox.io.readDocument(filename.startsWith("/") ? filename : (IO.WORKSPACE + "/" + filename), special, created);
		Log.println("io.debug", "created :" + created);

		Drawing.dirty(root);

		return created;

	}


	public boolean defaultGLPreamble(int pass) {


		GraphicsContext.getContext().stateTracker.viewport.set(new int[]{0, 0, window.getFrameBufferWidth(), window.getFrameBufferHeight()});
		GraphicsContext.getContext().stateTracker.scissor.set(new int[]{0, 0, window.getFrameBufferWidth(), window.getFrameBufferHeight()});
		glClearColor((float) Colors.backgroundColor.x, (float) Colors.backgroundColor.y, (float) Colors.backgroundColor.z, 1);
		glClear(GL11.GL_COLOR_BUFFER_BIT);
		glEnable(GL11.GL_BLEND);
		glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL11.GL_DEPTH_TEST);


		if (Main.os == Main.OS.linux)
			glEnable(GL13.GL_MULTISAMPLE);

		return true;
	}


	public boolean defaultGLPreambleBackground(int pass) {

		GraphicsContext.getContext().stateTracker.viewport.set(new int[]{0, 0, window.getFrameBufferWidth(), window.getFrameBufferHeight()});
		GraphicsContext.getContext().stateTracker.scissor.set(new int[]{0, 0, window.getFrameBufferWidth(), window.getFrameBufferHeight()});
		glEnable(GL11.GL_BLEND);
		glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL11.GL_DEPTH_TEST);
		if (Main.os == Main.OS.linux)
			glEnable(GL13.GL_MULTISAMPLE);

		return true;
	}

	public boolean defaultGLPreambleTransparent(int pass) {

		GraphicsContext.getContext().stateTracker.viewport.set(new int[]{0, 0, window.getFrameBufferWidth(), window.getFrameBufferHeight()});
		GraphicsContext.getContext().stateTracker.scissor.set(new int[]{0, 0, window.getFrameBufferWidth(), window.getFrameBufferHeight()});
		glClearColor((float) Colors.backgroundColor.x, (float) Colors.backgroundColor.y, (float) Colors.backgroundColor.z, 0);
		glClear(GL11.GL_COLOR_BUFFER_BIT);
		glEnable(GL11.GL_BLEND);
		glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL11.GL_DEPTH_TEST);
		if (Main.os == Main.OS.linux)
			glEnable(GL13.GL_MULTISAMPLE);

		return true;
	}
}
