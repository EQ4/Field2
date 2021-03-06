package field.graphics;

import com.badlogic.jglfw.Glfw;
import com.badlogic.jglfw.GlfwCallback;
import com.badlogic.jglfw.GlfwCallbackAdapter;
import field.CanonicalModifierKeys;
import field.app.RunLoop;
import field.app.ThreadSync;
import field.graphics.util.KeyEventMapping;
import field.linalg.Vec2;
import field.utility.*;
import fieldagent.Main;
import fieldbox.boxes.Mouse;
import fieldlinker.Linker;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.badlogic.jglfw.Glfw.*;

/**
 * An Window with an associated OpenGL draw context, and a base Field graphics Scene
 */
public class Window implements ProvidesGraphicsContext {

	static public final Dict.Prop<Boolean> consumed = new Dict.Prop<>("consumed").type()
										     .doc("marks an event as handled elsewhere")
										     .toCannon();
	static ThreadLocal<Window> currentWindow = new ThreadLocal<Window>() {
		@Override
		protected Window initialValue() {
			return null;
		}
	};

	private final int retinaScaleFactor;

	/**
	 * the Scene associated with this window.
	 * <p>
	 * This is the principle entry point into this window
	 */

	public final Scene scene = new Scene();
	private final long windowOpenedAt;
	private final CanonicalModifierKeys modifiers;
	private final GLCapabilities glcontext;
	private final Consumer<Integer> perform = (i) -> loop();
	volatile boolean isThreaded = false;
	protected GraphicsContext graphicsContext;
	protected long window;

	protected int w, x;
	protected int h, y;

	protected MouseState mouseState = new MouseState();
	protected KeyboardState keyboardState = new KeyboardState();
	int tick = 0;
	Queue<Function<Event<KeyboardState>, Boolean>> keyboardHandlers = new LinkedBlockingQueue<>();
	Queue<Function<Event<MouseState>, Boolean>> mouseHandlers = new LinkedBlockingQueue<>();
	Queue<Function<Event<Drop>, Boolean>> dropHandlers = new LinkedBlockingQueue<>();

	public IdempotencyMap<Mouse.OnMouseDown> onMouseDown = new IdempotencyMap<>(Mouse.OnMouseDown.class);
	public IdempotencyMap<Mouse.OnMouseScroll> onMouseScroll = new IdempotencyMap<>(Mouse.OnMouseScroll.class);
	Map<Integer, Collection<Mouse.Dragger>> ongoingDrags = new HashMap<Integer, Collection<Mouse.Dragger>>();


	private Rect currentBounds;

	static public boolean doubleBuffered = Options.dict()
						      .isTrue(new Dict.Prop("doubleBuffered"), true);

	static public Window shareContext = null;
	protected final Window shareContextAtConstruction ;

	private Thread onlyThread = null;

	public Window(int x, int y, int w, int h, String title) {
		this(x, y, w, h, title, true);
	}

	public Window(int x, int y, int w, int h, String title, boolean permitRetina) {
		Windows.windows.init();
		currentBounds = new Rect(x, y, w, h);

		shareContextAtConstruction = shareContext;
		if (shareContext==null) {
			shareContext = this;
			graphicsContext = GraphicsContext.newContext();
		}
		else
			graphicsContext = shareContext.graphicsContext;



		glfwWindowHint(GLFW_DEPTH_BITS, 24);
		glfwWindowHint(GLFW_SAMPLES, 8);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1);
		if (Main.os.equals(Main.OS.mac)) {
			glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
			glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
		} else {
			glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
			glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
		}

		glfwWindowHint(GLFW_DOUBLEBUFFER, doubleBuffered ? 1 : 0);

		glfwWindowHint(GLFW_DECORATED, title == null ? 0 : 1);

		this.w = w;
		this.h = h;
		this.x = x;
		this.y = y;

		window = glfwCreateWindow(w, h, title, 0, shareContext==this ? 0 : shareContext.window);
		Windows.windows.register(window, makeCallback());

		glfwSetWindowPos(window, x, y);
		Windows.windows.register(window, makeCallback());

		glfwShowWindow(window);

		glfwMakeContextCurrent(window);
		glfwSwapInterval(1);

		glfwWindowShouldClose(window);

		if (shareContext==this)
		{
			glcontext = GL.createCapabilities();
		}
		else
		{
			glcontext = shareContext.glcontext;
			GL.setCapabilities(glcontext);
		}



		GL11.glClearColor(0.25f, 0.25f, 0.25f, 1);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		glfwSwapBuffers(window);

		RunLoop.main.getLoop()
			    .attach(0, perform);


		Glfw.glfwSetInputMode(window, Glfw.GLFW_STICKY_MOUSE_BUTTONS, GL11.GL_TRUE);

		retinaScaleFactor = permitRetina ? (int) (Options.dict()
								 .getFloat(new Dict.Prop<Number>("retina"), 0f) + 1) : 1;

		windowOpenedAt = RunLoop.tick;


		modifiers = new CanonicalModifierKeys(window);

		new Thread() {
			long lastAt = System.currentTimeMillis();
			long lastWas = 0;

			public void run() {

				while (true) {
					if (System.currentTimeMillis() - lastAt > 5000) {
						double f = 1000 * (frame - lastWas) / (float) (System.currentTimeMillis() - lastAt);
						if (lastWas > 0 && (frame != lastWas)) System.err.println(" frame rate is :" + f + " for " + Window.this);
						lastWas = frame;
						lastAt = System.currentTimeMillis();
					}
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {


					}
				}
			}
		}.start();


	}

	public void goThreaded() {
		if (shareContextAtConstruction!=null) throw new IllegalArgumentException(" can't multithread a window if it's sharing a context on construciton. Set Window.shareContext to null before creating window");
		RunLoop.main.getLoop()
			    .detach(perform);
		isThreaded = true;


		new Thread() {
			@Override
			public void run() {
				onlyThread = Thread.currentThread();

				while (true) try {
					if (graphicsContext.lock.tryLock(1, TimeUnit.DAYS)) {
						try {
							loop();
						} catch (Throwable t) {
							t.printStackTrace();
						}
					} else {
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					graphicsContext.lock.unlock();
				}
			}
		}.start();

	}

	static boolean debugKeyboardTransition(Event<KeyboardState> event) {
		Set<Character> pressed = KeyboardState.charsPressed(event.before, event.after);
		Set<Character> released = KeyboardState.charsReleased(event.before, event.after);
		Set<Integer> kpressed = KeyboardState.keysPressed(event.before, event.after);
		Set<Integer> kreleased = KeyboardState.keysReleased(event.before, event.after);

		if (pressed.size() > 0) System.out.print("down<" + pressed + ">");
		if (kpressed.size() > 0) System.out.print("down<" + kpressed + ">");
		if (released.size() > 0) System.out.print("up<" + released + ">");
		if (kreleased.size() > 0) System.out.print("up<" + kreleased + ">");
		if (event.after.keysDown.size() > 0) System.out.println(" now<" + event.after.keysDown + ">");
		return true;
	}

	static boolean debugMouseTransition(Event<MouseState> event) {
		Set<Integer> pressed = MouseState.buttonsPressed(event.before, event.after);
		Set<Integer> released = MouseState.buttonsReleased(event.before, event.after);
		if (pressed.size() > 0) System.out.print("down<" + pressed + ">");
		if (released.size() > 0) System.out.print("up<" + released + ">");
		return true;
	}

	static public int getCurrentWidth() {
		if (currentWindow.get() == null) throw new IllegalArgumentException(" no window mouseState ");
		return currentWindow.get().w;
	}

	static public int getCurrentHeight() {
		if (currentWindow.get() == null) throw new IllegalArgumentException(" no window mouseState ");
		return currentWindow.get().h;
	}


	private int frameHack = 0;

	public interface SwapControl {
		public void swap(long window);
	}

	public SwapControl swapControl = (w) -> glfwSwapBuffers(w);

	public interface RenderControl {
		public boolean skipRender();
	}

	public RenderControl renderControl = () -> false;

	public void loop() {

		currentWindow.set(this);
		try {
			if (onlyThread != null && Thread.currentThread() != onlyThread) throw new Error();

			if (frame == 10) glfwSetWindowPos(window, x, y);

			if (Main.os== Main.OS.mac) {
				// shenanegians required to order front a window on El Capitan
				if (frameHack++ == 0) {
					Rect r = getBounds();
					setBounds((int) r.x, (int) r.y, (int) r.w, (int) r.h + 1);
				}
				if (frameHack == 1) {
					Rect r = getBounds();
					setBounds((int) r.x, (int) r.y, (int) r.w, (int) r.h - 1);
				}
				if (frameHack == 2) {
					Rect r = getBounds();
					setBounds((int) r.x, (int) r.y, (int) r.w, (int) r.h + 1);
				}
				if (frameHack == 3) {
					Rect r = getBounds();
					setBounds((int) r.x, (int) r.y, (int) r.w, (int) r.h - 1);
				}
			}
			if (!needsRepainting()) {
				if (!isThreaded) glfwPollEvents();
				return;
			}

			if (renderControl.skipRender()) {
				System.out.println(" skipping render ");
				if (!isThreaded) glfwPollEvents();
				return;
			}

			needsRepainting = false;

			glfwMakeContextCurrent(window);
			glfwSwapInterval(1);

			// makes linux all go to hell
			//glcontext.makeCurrent(0);
			GL.setCapabilities(glcontext);

			int w = glfwGetWindowWidth(window);
			int h = glfwGetWindowHeight(window);

			if (w != this.w || h != this.h) {
				GraphicsContext.isResizing = true;
				this.w = w;
				this.h = h;
			} else {
				GraphicsContext.isResizing = false;
			}


			updateScene();

			frame++;
			if (!dontSwap) {
				swapControl.swap(window);
			}

			if (!isThreaded) glfwPollEvents();
		} finally {
			currentWindow.set(null);
		}

	}

	long frame;
	public boolean dontSwap = false;

	protected boolean disabled = false;
	protected boolean lazyRepainting = false;
	protected boolean needsRepainting = false;

	/**
	 * stops this canvas from repainting, under any conditions
	 */
	public void disable() {
		disabled = true;
	}

	/**
	 * allows this canvas to repaint
	 */
	public void enable() {
		disabled = false;
	}

	/**
	 * sets whether this canvas updates and repaints constantly (every animation cycle) or only once when repaint has been called
	 */
	public void setLazyRepainting(boolean b) {
		lazyRepainting = b;
	}

	/**
	 * requests that this canvas repaints itself this animation cycle. Note, that you should almost certainly setLazyRepainting(true) before you call this, otherwise the canvas will update every
	 * cycle whether you call this or not.
	 */
	public void requestRepaint() {
		needsRepainting = true;
	}

	/**
	 * By default, we are animated, and we draw every frame. Some subclasses (notably FieldBoxWindow) will finesse this down a bit, but a standard graphics window has a traditional
	 * "draw-every-frame" draw loop
	 */
	protected boolean needsRepainting() {
		return !disabled && (!lazyRepainting || needsRepainting);
	}

	public void setTitle(String title) {
		glfwSetWindowTitle(window, title);
	}

	public void setBounds(int x, int y, int w, int h) {
		glfwSetWindowSize(window, w, h);
		glfwSetWindowPos(window, x, y);
		glfwSetWindowSize(window, w, h);
		glfwSetWindowPos(window, x, y);
		currentBounds = new Rect(x, y, w, h);
	}


	public Rect getBounds() {
		if (currentBounds==null)
			currentBounds = new Rect(0,0,500,500);
		currentBounds.x = glfwGetWindowX(window);
		currentBounds.y = glfwGetWindowY(window);
		currentBounds.w = glfwGetWindowWidth(window);
		currentBounds.h = glfwGetWindowHeight(window);
		return currentBounds;
	}

	public Rect getFramebufferBounds() {
		return new Rect(currentBounds.x, currentBounds.y, getFrameBufferWidth(), getFrameBufferHeight());
	}

	protected void updateScene() {
		GraphicsContext.enterContext(graphicsContext);
		GraphicsContext.checkError(() -> "initially");
		try {
			GraphicsContext.getContext().stateTracker.viewport.set(new int[]{0, 0, w * getRetinaScaleFactor(), h * getRetinaScaleFactor()});
			GraphicsContext.getContext().stateTracker.scissor.set(new int[]{0, 0, w * getRetinaScaleFactor(), h * getRetinaScaleFactor()});
			GraphicsContext.getContext().stateTracker.fbo.set(0);
			GraphicsContext.getContext().stateTracker.shader.set(0);
			GraphicsContext.getContext().stateTracker.blendState.set(new int[]{GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA});

			Log.log("graphics.trace", () -> "scene is ...\n" + scene.debugPrintScene());

			scene.updateAll();
		} finally {
			GraphicsContext.exitContext(graphicsContext);
		}
	}

	public GraphicsContext getGraphicsContext() {
		if (graphicsContext==null)
			System.err.println("WARNING: no graphics context for window");
		return graphicsContext;
	}

	/**
	 * gets the current clipboard (as a string);
	 */
	public String getCurrentClipboard() {
		return glfwGetClipboardString(window);
	}

	/**
	 * sets the current clipboard (as a string);
	 */
	public void setCurrentClipboard(String s) {
		glfwSetClipboardString(window, s);
	}

	/**
	 * returns the internal glfw window handle for this window. You'll only need this if you are going to do GLFW stuff to this window)
	 */
	public long getGLFWWindowReference() {
		return window;
	}

	/**
	 * returns the last seen mouse state
	 */
	public MouseState getCurrentMouseState() {
		return mouseState;
	}

	/**
	 * A keyboard handler is a Function<Event<KeyboardState>, Boolean>>, that is a function that takes a transition between two KeyboardStates and returns a boolean, whether or not it ever wants
	 * to be called again
	 */
	public Window addKeyboardHandler(Function<Event<KeyboardState>, Boolean> h) {
		keyboardHandlers.add(h);
		return this;
	}

	/**
	 * A keyboard handler is a Function<Event<MouseState>, Boolean>>, that is a function that takes a transition between two MouseStates and returns a boolean, whether or not it ever wants to be
	 * called again
	 */
	public Window addMouseHandler(Function<Event<MouseState>, Boolean> h) {
		mouseHandlers.add(h);
		return this;
	}

	/**
	 * A keyboard handler is a Function<Event<Drop>, Boolean>>, that is a function that takes a transition between null and a Drop and returns a boolean, whether or not it ever wants to be called
	 * again
	 * <p>
	 * (we expect that as GLFW's notion of drop handling gets richer we'll have a use for Event.before)
	 */
	public Window addDropHandler(Function<Event<Drop>, Boolean> h) {
		dropHandlers.add(h);
		return this;
	}

	private void fireMouseTransition(MouseState before, MouseState after) {
		after.keyboardState = keyboardState;

		if ((after.mods & Glfw.GLFW_MOD_SHIFT) != 0) after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_SHIFT, true);
		else after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_SHIFT, false)
							      .withKey(Glfw.GLFW_KEY_RIGHT_SHIFT, false);
		if ((after.mods & Glfw.GLFW_MOD_CONTROL) != 0) after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_CONTROL, true);
		else after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_CONTROL, false)
							      .withKey(Glfw.GLFW_KEY_RIGHT_CONTROL, false);
		if ((after.mods & Glfw.GLFW_MOD_ALT) != 0) after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_ALT, true);
		else after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_ALT, false)
							      .withKey(Glfw.GLFW_KEY_RIGHT_ALT, false);
		if ((after.mods & Glfw.GLFW_MOD_SUPER) != 0) after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_SUPER, true);
		else after.keyboardState = after.keyboardState.withKey(Glfw.GLFW_KEY_LEFT_SUPER, false)
							      .withKey(Glfw.GLFW_KEY_RIGHT_SUPER, false);

		Iterator<Function<Event<MouseState>, Boolean>> i = mouseHandlers.iterator();
		Event<MouseState> event = new Event<>(before, after);
		while (i.hasNext()) if (!i.next()
					  .apply(event)) i.remove();

		doHighLevelMouseEvents(event);
	}

	private void fireMouseTransitionNoMods(MouseState before, MouseState after) {
		after.keyboardState = keyboardState;

		Iterator<Function<Event<MouseState>, Boolean>> i = mouseHandlers.iterator();
		Event<MouseState> event = new Event<>(before, after);
		while (i.hasNext()) if (!i.next()
					  .apply(event)) i.remove();

		doHighLevelMouseEvents(event);
	}

	private void doHighLevelMouseEvents(Event<MouseState> event) {

		Set<Integer> pressed = Window.MouseState.buttonsPressed(event.before, event.after);
		Set<Integer> released = Window.MouseState.buttonsReleased(event.before, event.after);
		Set<Integer> down = event.after.buttonsDown;

		released.stream()
			.map(r -> ongoingDrags.remove(r))
			.filter(x -> x != null)
			.forEach(drags -> {
				drags.stream()
				     .filter(d -> d != null)
				     .forEach(dragger -> dragger.update(event, true));
				drags.clear();
			});

		for (Collection<Mouse.Dragger> d : ongoingDrags.values()) {
			Iterator<Mouse.Dragger> dd = d.iterator();
			while (dd.hasNext()) {
				Mouse.Dragger dragger = dd.next();
				boolean cont = false;
				try {
					cont = dragger.update(event, false);
				} catch (Throwable t) {
					System.err.println(" Exception thrown in dragger update :" + t);
					System.err.println(" dragger will not be called again");
					cont = false;
				}
				if (!cont) dd.remove();
			}
		}

		Util.Errors errors = new Util.Errors();


		if (event.after.dwheely != 0.0 || event.after.dwheel != 0.0) onMouseScroll.values()
											  .forEach(Util.wrap(x -> x.onMouseScroll(event), errors));


		pressed.stream()
		       .forEach(p -> {
			       Collection<Mouse.Dragger> dragger = ongoingDrags.computeIfAbsent(p, (x) -> new ArrayList<>());

			       onMouseDown.values()
					  .stream()
					  .map(Util.wrap(x -> x.onMouseDown(event, p), errors, null, Mouse.Dragger.class))
					  .filter(x -> x != null)
					  .collect(Collectors.toCollection(() -> dragger));
		       });

		if (errors.hasErrors()) {
			errors.getErrors()
			      .forEach(x -> {
				      x.first.printStackTrace();
			      });
		}
	}

	private void fireKeyboardTransition(KeyboardState before, KeyboardState after) {
		after.mouseState = mouseState;
		Iterator<Function<Event<KeyboardState>, Boolean>> i = keyboardHandlers.iterator();
		Event<KeyboardState> event = new Event<>(before, after);
		while (i.hasNext()) if (!i.next()
					  .apply(event)) i.remove();
	}

	private void fireDrop(Drop drop) {
		Iterator<Function<Event<Drop>, Boolean>> i = dropHandlers.iterator();
		Event<Drop> event = new Event<>(null, drop);
		while (i.hasNext()) if (!i.next()
					  .apply(event)) i.remove();
	}

	public int getWidth() {
		return w;
	}

	public int getHeight() {
		return h;
	}

	public int getFrameBufferWidth() {
		return glfwGetFramebufferWidth(window);
		//return w * retinaScaleFactor;
	}

	public int getFrameBufferHeight() {
		return glfwGetFramebufferHeight(window);
		//return h * retinaScaleFactor;
	}

	protected GlfwCallback makeCallback() {
		return new GlfwCallbackAdapter() {

			int event = 0;

			@Override
			public void error(int error, String description) {
				System.err.println(" ERROR in GLFW windowing system :" + error + " / " + description);
			}

			@Override
			public void windowRefresh(long window) {
			}

			@Override
			public void mouseButton(long window, int button, boolean pressed, int mods) {
				if (window == Window.this.window) {
					MouseState next = mouseState.withButton(button, pressed, mods);
					fireMouseTransition(mouseState, next);
					mouseState = next;
				}
			}

			@Override
			public void windowFocus(long window, boolean focused) {
				keyboardState.keysDown.clear();
			}

			@Override
			public void scroll(long window, double scrollX, double scrollY) {
				if (window == Window.this.window) {
					MouseState next = mouseState.withScroll(scrollX, scrollY);
					next.keyboardState = keyboardState;
					fireMouseTransitionNoMods(mouseState, next);
					next = mouseState.withScroll(0, 0);
					mouseState = next;
					next.keyboardState = keyboardState;
				}
			}

			@Override
			public void cursorPos(long window, double x, double y) {
				if (window == Window.this.window) {
					MouseState next = mouseState.withPosition(x, y);
					fireMouseTransition(mouseState, next);
					mouseState = next;
				}
			}

			@Override
			public void key(long window, int key, int scancode, int action, int mods) {
				if (window == Window.this.window && RunLoop.tick > windowOpenedAt + 10) { // we ignore keyboard events from the first couple of updates; they can refer to key downs that we'll never recieve up fors


					KeyboardState next = keyboardState.withKey(key, action != GLFW_RELEASE);

					modifiers.event(key, scancode, action, mods, next.keysDown);


					next = modifiers.cleanModifiers(next);
					next = next.clean(window);

					fireKeyboardTransition(keyboardState, next);
					keyboardState = next;
				}
			}

			@Override
			public void character(long window, char character) {
				if (window == Window.this.window) {
					KeyboardState next = keyboardState.withChar(character, true);

					boolean shift = (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_LEFT_SHIFT)) || (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_RIGHT_SHIFT));
					boolean alt = (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_LEFT_ALT)) || (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_RIGHT_ALT));
					boolean meta = (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_LEFT_SUPER)) || (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_RIGHT_SUPER));
					boolean ctrl = (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_LEFT_CONTROL)) || (Glfw.glfwGetKey(window, Glfw.GLFW_KEY_RIGHT_CONTROL));

					next = modifiers.cleanModifiers(next);
					next = next.clean(window);

					fireKeyboardTransition(keyboardState, next);
					keyboardState = next;
					next = keyboardState.withChar(character, false);

					next = modifiers.cleanModifiers(next);
					next = next.clean(window);
					fireKeyboardTransition(keyboardState, next);
					keyboardState = next;
				}
			}

			@Override
			public void drop(long window, String[] files) {
				if (window == Window.this.window) {
					fireDrop(new Drop(files, mouseState, keyboardState));
				}
			}

			@Override
			public void windowPos(long window, int x, int y) {
				if (window == Window.this.window) {
					currentBounds.x = x;
					currentBounds.y = y;
				}
			}

			@Override
			public void windowSize(long window, int width, int height) {
				if (window == Window.this.window) {
					currentBounds.w = width;
					currentBounds.h = height;
				}
			}

			@Override
			public void framebufferSize(long window, int width, int height) {

			}
		};
	}

	public int getRetinaScaleFactor() {
		int sc = glfwGetFramebufferWidth(window) / glfwGetWindowWidth(window);
//		System.err.println("SC:"+sc);
		return sc;
	}

	public interface HasPosition {
		Optional<Vec2> position();
	}

	static public class MouseState implements HasPosition {
		public final Set<Integer> buttonsDown = new LinkedHashSet<Integer>();
		public final long time;

		public final double dx;
		public final double dy;
		public final double x;
		public final double y;


		public double mx; // in drawing space
		public double my;
		public double mdx;
		public double mdy;


		public final float dwheel;
		public final float dwheely;
		public final int mods;

		// not final (but still immutable), not part of the transition framework, just along to reduce static access to Window
		public KeyboardState keyboardState;

		public MouseState() {
			time = 0;
			dx = 0;
			dy = 0;
			x = 0;
			y = 0;
			dwheel = 0;
			dwheely = 0;
			mods = 0;
		}

		public MouseState(Set<Integer> buttonsDown, double x, double y, float dwheel, float dwheely, double dx, double dy, long time, int mods) {
			this.x = x;
			this.y = y;
			this.dwheel = dwheel;
			this.dwheely = dwheely;
			this.dx = dx;
			this.dy = dy;
			this.time = time;
			this.buttonsDown.addAll(buttonsDown);
			this.mods = mods;
		}

		static public Set<Integer> buttonsPressed(MouseState before, MouseState after) {
			Set<Integer> b = new LinkedHashSet<Integer>(after.buttonsDown);
			b.removeAll(before.buttonsDown);
			return b;
		}

		static public Set<Integer> buttonsReleased(MouseState before, MouseState after) {
			Set<Integer> b = new LinkedHashSet<Integer>(before.buttonsDown);
			b.removeAll(after.buttonsDown);
			return b;
		}

		public MouseState next(int button, float dwheel, int dx, int dy, double x, double y, boolean bs, long time, int mods) {
			Set<Integer> buttonsDown = new LinkedHashSet<Integer>(this.buttonsDown);
			if (bs && button != -1) buttonsDown.add(button);
			else if (!bs && button != -1) buttonsDown.remove(button);

			return new MouseState(buttonsDown, x, y, dwheel, dwheely, dx, dy, time, mods);
		}

		public MouseState withButton(int button, boolean bs, int mods) {
			Set<Integer> buttonsDown = new LinkedHashSet<Integer>(this.buttonsDown);
			if (bs && button != -1) buttonsDown.add(button);
			else if (!bs && button != -1) buttonsDown.remove(button);
			MouseState m = new MouseState(buttonsDown, x, y, dwheel, dwheely, 0, 0, time, mods);
			return m;
		}

		public MouseState withMods(int mods) {
			return new MouseState(buttonsDown, x, y, dwheel, dwheely, x - this.x, y - this.y, time, mods);
		}

		// currently we ignore sy
		public MouseState withScroll(double sx, double sy) {
			return new MouseState(buttonsDown, x, y, (float) sx, (float) sy, dx, dy, time, mods);
		}

		public MouseState withPosition(double x, double y) {
			return new MouseState(buttonsDown, x, y, dwheel, dwheely, x - this.x, y - this.y, time, mods);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof MouseState)) return false;

			MouseState that = (MouseState) o;

			if (dwheel != that.dwheel) return false;
			if (x != that.x) return false;
			if (y != that.y) return false;
			return buttonsDown.equals(that.buttonsDown);

		}

		@Override
		public int hashCode() {
			int result;
			long temp;
			temp = Double.doubleToLongBits(x);
			result = (int) (temp ^ (temp >>> 32));
			temp = Double.doubleToLongBits(y);
			result = 31 * result + (int) (temp ^ (temp >>> 32));
			result = 31 * result + (dwheel != +0.0f ? Float.floatToIntBits(dwheel) : 0);
			return result;
		}

		@Override
		public String toString() {
			return "MouseState{" +
				    "buttonsDown=" + buttonsDown +
				    ", x=" + x +
				    ", y=" + y +
				    ", dwheel=" + dwheel +
				    ", time=" + time +
				    '}';
		}

		@Override
		public Optional<Vec2> position() {
			return Optional.of(new Vec2(x, y));
		}
	}

	static public class KeyboardState implements HasPosition {
		public final Set<Integer> keysDown = new LinkedHashSet<>();
		public final Map<Integer, Character> charsDown = new LinkedHashMap<>();
		public final long time;

		// not final (but still immutable), not part of the transition framework, just along to reduce static access to Window
		public MouseState mouseState;

		public KeyboardState() {
			time = 0;
		}

		public KeyboardState(Set<Integer> keysDown, Map<Integer, Character> charsDown, long time) {
			this.keysDown.addAll(keysDown);
			this.charsDown.putAll(charsDown);
			this.time = time;
		}

		static public Set<Integer> keysPressed(KeyboardState before, KeyboardState after) {
			Set<Integer> b = new LinkedHashSet<>(after.keysDown);
			b.removeAll(before.keysDown);
			return b;
		}

		static public Set<Integer> keysReleased(KeyboardState before, KeyboardState after) {
			Set<Integer> b = new LinkedHashSet<>(before.keysDown);
			b.removeAll(after.keysDown);
			return b;
		}

		static public Set<Character> charsPressed(KeyboardState before, KeyboardState after) {
			Set<Character> b = new LinkedHashSet<>(after.charsDown.values());
			b.removeAll(before.charsDown.values());
			return b;
		}

		static public Set<Character> charsReleased(KeyboardState before, KeyboardState after) {
			Set<Character> b = new LinkedHashSet<>(before.charsDown.values());
			b.removeAll(after.charsDown.values());
			return b;
		}

		public KeyboardState next(int key, char character, boolean state, long time) {
			Set<Integer> keysDown = new LinkedHashSet<>(this.keysDown);
			Map<Integer, Character> charsDown = new LinkedHashMap<>(this.charsDown);
			if (state) {
				keysDown.add(key);
				charsDown.put(key, character);
			} else {
				keysDown.remove(key);
				charsDown.remove(key);
			}

			return new KeyboardState(keysDown, charsDown, time);
		}

		public KeyboardState withKey(int key, boolean down) {
			Set<Integer> keysDown = new LinkedHashSet<>(this.keysDown);
			Map<Integer, Character> charsDown = new LinkedHashMap<>(this.charsDown);
			if (down) {
				keysDown.add(key);
			} else {
				keysDown.remove(key);
			}

			return new KeyboardState(keysDown, charsDown, time);
		}

		public KeyboardState withChar(char c, boolean down) {
			Set<Integer> keysDown = new LinkedHashSet<>(this.keysDown);
			Map<Integer, Character> charsDown = new LinkedHashMap<>(this.charsDown);
			if (down) {
				charsDown.put((int) c, c);
			} else {
				charsDown.remove((int) c);
			}

			return new KeyboardState(keysDown, charsDown, time);
		}

		public boolean isShiftDown() {
			return keysDown.contains(Glfw.GLFW_KEY_LEFT_SHIFT) || keysDown.contains(Glfw.GLFW_KEY_RIGHT_SHIFT);
		}

		public boolean isAltDown() {
			return keysDown.contains(Glfw.GLFW_KEY_LEFT_ALT) || keysDown.contains(Glfw.GLFW_KEY_RIGHT_ALT);
		}

		public boolean isSuperDown() {
			return keysDown.contains(Glfw.GLFW_KEY_LEFT_SUPER) || keysDown.contains(Glfw.GLFW_KEY_RIGHT_SUPER);
		}

		public boolean isControlDown() {
			return keysDown.contains(Glfw.GLFW_KEY_LEFT_CONTROL) || keysDown.contains(Glfw.GLFW_KEY_RIGHT_CONTROL);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof KeyboardState)) return false;

			KeyboardState that = (KeyboardState) o;

			if (!charsDown.equals(that.charsDown)) return false;
			return keysDown.equals(that.keysDown);

		}

		@Override
		public int hashCode() {
			int result = keysDown.hashCode();
			result = 31 * result + charsDown.hashCode();
			return result;
		}

		@Override
		public String toString() {
			return "KeyboardState{" +
				    "keysDown=" + keysDown +
				    ", charsDown=" + charsDown +
				    ", time=" + time +
				    '}';
		}

		public Optional<Vec2> position() {
			if (mouseState == null) return Optional.empty();
			return mouseState.position();
		}


		public KeyboardState clean(long window) {
			Set<Integer> k = new LinkedHashSet<>(keysDown);
			Iterator<Integer> ii = k.iterator();
			while (ii.hasNext()) {
				Integer m = ii.next();

				if (m == Glfw.GLFW_KEY_LEFT_SHIFT || m == Glfw.GLFW_KEY_RIGHT_SHIFT || m == Glfw.GLFW_KEY_LEFT_ALT || m == Glfw.GLFW_KEY_RIGHT_ALT || m == Glfw.GLFW_KEY_LEFT_SUPER || m == Glfw.GLFW_KEY_RIGHT_SUPER || m == Glfw.GLFW_KEY_LEFT_CONTROL || m == Glfw.GLFW_KEY_RIGHT_CONTROL)
					continue;

				boolean notReally = glfwGetKey(window, m);
				if (!notReally) {
					Log.log("keyboard.debug", () -> "Got an imposter :" + m + " " + KeyEventMapping.lookup(m));
					ii.remove();
					charsDown.remove(m);
				} else {
				}
			}

			return new KeyboardState(k, charsDown, time);
		}
	}

	static public class Drop implements HasPosition {
		public final String[] files;
		public final MouseState mouseState;
		public final KeyboardState keyboardState;

		public Drop(String[] files, MouseState mouseState, KeyboardState keyboardState) {
			this.files = files;
			this.mouseState = mouseState;
			this.keyboardState = keyboardState;
		}

		@Override
		public Optional<Vec2> position() {
			if (mouseState == null) return Optional.empty();
			return mouseState.position();
		}
	}

	static public class Event<T> extends AsMapDelegator {
		public final T before;
		public final T after;

		public final Dict properties = new Dict();

		public Event(T before, T after) {
			this.before = before;
			this.after = after;
		}

		public String toString() {
			return "ev<" + before + "->" + after + ">";
		}

		public Event<T> copy() {
			Event<T> e = new Event(before, after);
			e.properties.putAll(properties);
			return e;
		}

		@Override
		protected Linker.AsMap delegateTo() {
			return properties;
		}
	}

}
