package dev.octoberjourney.popup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import dev.gatehouse.look.Quiet;

/** Reads a game controller for the launcher's windows, so they can be driven without a mouse: the Steam Deck's own
 *  controls, a Steam Controller, or any pad Steam (or Windows) presents as an Xbox-style controller. The Steam layout
 *  the shortcuts use stays a gamepad layout, as the game needs; the launcher reads the same pad.
 *    Windows: XInput, through Java's own foreign-function support (PadWin; Java 22 or newer, else no controller here).
 *    Linux:   the kernel's joystick devices (/dev/input/js*), which Prism's Flatpak can read.
 *  It reports presses as words: a, b, x, y, start, back, up, down, left, right (d-pad or left stick, repeating while
 *  held), rt-down / rt-up (the right trigger, used as a mouse click), and scroll:<pixels> while the right stick is
 *  pushed up or down (R23: it scrolls, faster the further it is pushed). Nothing is read while no launcher window is
 *  showing, and nothing is ever sent to the controller. */
final class Pad {
	private Pad() {}

	// What is held right now, from whichever source.
	static final int A = 0, B = 1, X = 2, Y = 3, START = 4, BACK = 5, UP = 6, DOWN = 7, LEFT = 8, RIGHT = 9, RT = 10, LB = 11, RB = 12, COUNT = 13;
	static final String[] WORDS = {"a", "b", "x", "y", "start", "back", "up", "down", "left", "right", "rt", "lb", "rb"};
	private static final boolean[] held = new boolean[COUNT];
	/** The right stick, up and down (R23: it scrolls): -32768 (up) to 32767 (down). */
	private static volatile int rightY;
	private static volatile boolean started;
	static volatile String source = "none";

	/** Starts reading (once). Each press is handed to the listener on a background thread. */
	static synchronized void start(Consumer<String> listener) {
		if (started) return;
		started = true;
		boolean win = System.getProperty("os.name", "").startsWith("Windows");
		java.util.function.Supplier<int[]> xinput = null;
		if (win) try {
			@SuppressWarnings("unchecked") java.util.function.Supplier<int[]> s = (java.util.function.Supplier<int[]>) Class.forName("dev.octoberjourney.popup.PadWin").getDeclaredConstructor().newInstance();
			xinput = s;
			source = "XInput";
		} catch (Throwable t) { source = "none (this Java can't read XInput: " + t.getClass().getSimpleName() + ")"; }
		else linux();
		java.util.function.Supplier<int[]> xi = xinput;
		Thread t = new Thread(() -> {
			boolean[] was = new boolean[COUNT];
			long[] since = new long[COUNT], last = new long[COUNT];
			while (true) {
				try {
					boolean showing = false;
					for (java.awt.Window w : java.awt.Window.getWindows()) if (w.isShowing()) showing = true;
					if (!showing && System.getenv("OJ_PAD_TEST") == null) { Thread.sleep(400); java.util.Arrays.fill(was, false); continue; }
					if (xi != null) {
						int[] s = xi.get();   // {buttons, right trigger 0-255, left stick x, left stick y} or null
						synchronized (held) {
							java.util.Arrays.fill(held, false);
							if (s != null) {
								int b = s[0];
								held[A] = (b & 0x1000) != 0; held[B] = (b & 0x2000) != 0; held[X] = (b & 0x4000) != 0; held[Y] = (b & 0x8000) != 0;
								held[START] = (b & 0x10) != 0; held[BACK] = (b & 0x20) != 0; held[LB] = (b & 0x100) != 0; held[RB] = (b & 0x200) != 0;
								held[UP] = (b & 0x1) != 0 || s[3] > 20000; held[DOWN] = (b & 0x2) != 0 || s[3] < -20000;
								held[LEFT] = (b & 0x4) != 0 || s[2] < -20000; held[RIGHT] = (b & 0x8) != 0 || s[2] > 20000;
								held[RT] = s[1] > 120;
								if (s.length > 5) rightY = -s[5];   // (XInput counts up as more)
							} else rightY = 0;
						}
					}
					boolean[] now;
					synchronized (held) { now = held.clone(); }
					long t0 = System.currentTimeMillis();
					for (int i = 0; i < COUNT; i++) {
						if (i == RT) { if (now[i] != was[i]) listener.accept(now[i] ? "rt-down" : "rt-up"); }
						else if (now[i] && !was[i]) { listener.accept(WORDS[i]); since[i] = t0; last[i] = t0; }
						else if (now[i] && i >= UP && i <= RIGHT && t0 - since[i] > 420 && t0 - last[i] > 140) { listener.accept(WORDS[i]); last[i] = t0; }   // (held: repeats)
						was[i] = now[i];
					}
					// (the right stick: past a small dead zone, a scroll each tick, faster the further it is pushed)
					int ry = rightY;
					if (Math.abs(ry) > 8000) {
						double f = (Math.abs(ry) - 8000) / 24767.0;
						listener.accept("scroll:" + (int) Math.round(Math.signum(ry) * (3 + 30 * f * f)));
					}
					Thread.sleep(30);
				} catch (InterruptedException e) { return; }
				catch (RuntimeException e) { try { Thread.sleep(500); } catch (InterruptedException ie) { return; } }
			}
		}, "pad");
		t.setDaemon(true);
		t.start();
	}

	/** Linux: one reader for each joystick device there is (and ones that appear later). An event is 8 bytes: time (4),
	 *  value (2, signed), type (1: 1 button, 2 axis; +0x80 for the state sent on opening), number (1). Numbers as an
	 *  Xbox-style pad has them: buttons 0 A, 1 B, 2 X, 3 Y, 6 Back, 7 Start; axes 0 and 1 the left stick, 5 the right
	 *  trigger, 6 and 7 the d-pad. */
	private static void linux() {
		List<String> open = new ArrayList<>();
		Thread finder = new Thread(() -> {
			while (true) {
				try {
					List<Path> devs = new ArrayList<>();
					String only = System.getenv("OJ_PAD_DEV");   // (testing: a file of recorded events)
					if (only != null) devs.add(Path.of(only));
					else try (java.util.stream.Stream<Path> s = Files.list(Path.of("/dev/input"))) { s.filter(p -> p.getFileName().toString().matches("js\\d+")).forEach(devs::add); } catch (IOException ignored) { Quiet.seen(ignored); }
					for (Path d : devs) {
						synchronized (open) { if (open.contains(d.toString())) continue; open.add(d.toString()); }
						source = "joystick devices (" + String.join(", ", open) + ")";
						Thread r = new Thread(() -> {
							boolean[] stickX = new boolean[2], stickY = new boolean[2], hatX = new boolean[2], hatY = new boolean[2];
							try (InputStream in = Files.newInputStream(d)) {
								byte[] e = new byte[8];
								while (in.readNBytes(e, 0, 8) == 8) {
									int value = (short) ((e[4] & 0xFF) | (e[5] << 8)), type = e[6] & 0x7F, n = e[7] & 0xFF;
									synchronized (held) {
										if (type == 1) {
											int k = n == 0 ? A : n == 1 ? B : n == 2 ? X : n == 3 ? Y : n == 4 ? LB : n == 5 ? RB : n == 6 ? BACK : n == 7 ? START : -1;
											if (k >= 0) held[k] = value != 0;
										} else if (type == 2) {
											if (n == 0) { stickX[0] = value < -20000; stickX[1] = value > 20000; }
											else if (n == 1) { stickY[0] = value < -20000; stickY[1] = value > 20000; }
											else if (n == 6) { hatX[0] = value < -16000; hatX[1] = value > 16000; }
											else if (n == 7) { hatY[0] = value < -16000; hatY[1] = value > 16000; }
											else if (n == 5) held[RT] = value > 0;
											else if (n == 4) rightY = value;   // (the right stick, up and down: down is more, as it is here)
											held[LEFT] = stickX[0] || hatX[0]; held[RIGHT] = stickX[1] || hatX[1];
											held[UP] = stickY[0] || hatY[0]; held[DOWN] = stickY[1] || hatY[1];
										}
									}
								}
							} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
							synchronized (held) { java.util.Arrays.fill(held, false); }
							rightY = 0;
							synchronized (open) { open.remove(d.toString()); }
						}, "pad-" + d.getFileName());
						r.setDaemon(true);
						r.start();
					}
					Thread.sleep(3000);
				} catch (InterruptedException e) { return; }
			}
		}, "pad-find");
		finder.setDaemon(true);
		finder.start();
	}
}
