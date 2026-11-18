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
 *  held), rt-down / rt-up (the right trigger, used as a mouse click), scroll:<pixels> while the right stick is
 *  pushed up or down (R23: it scrolls, faster the further it is pushed), and hscroll:<pixels> while it is pushed left
 *  or right (R41: sideways, the same way). Nothing is read while no launcher window is
 *  showing, and nothing is ever sent to the controller. */
final class Pad {
	private Pad() {}

	// What is held right now, from whichever source.
	static final int A = 0, B = 1, X = 2, Y = 3, START = 4, BACK = 5, UP = 6, DOWN = 7, LEFT = 8, RIGHT = 9, RT = 10, LB = 11, RB = 12, COUNT = 13;
	static final String[] WORDS = {"a", "b", "x", "y", "start", "back", "up", "down", "left", "right", "rt", "lb", "rb"};
	private static final boolean[] held = new boolean[COUNT];
	/** The right stick, up and down (R23: it scrolls): -32768 (up) to 32767 (down). */
	private static volatile int rightY;
	/** The right stick, left and right (R41: it scrolls sideways): -32768 (left) to 32767 (right). */
	private static volatile int rightX;
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
		else { linux(); deckPad(listener); }
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
								if (s.length > 5) { rightY = -s[5]; rightX = s[4]; }   // (XInput counts up as more; right as more)
							} else { rightY = 0; rightX = 0; }
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
					int ry = rightY, rx = rightX;
					if (Math.abs(ry) > 8000) listener.accept("scroll:" + speed(ry));
					if (Math.abs(rx) > 8000) listener.accept("hscroll:" + speed(rx));   // (R41: sideways too; both at once when pushed slantwise)
					Thread.sleep(30);
				} catch (InterruptedException e) { return; }
				catch (RuntimeException e) { try { Thread.sleep(500); } catch (InterruptedException ie) { return; } }
			}
		}, "pad");
		t.setDaemon(true);
		t.start();
	}

	/** Pixels to scroll this tick for a stick pushed this far past its dead zone: faster the further it is pushed. */
	static int speed(int v) {
		double f = (Math.abs(v) - 8000) / 24767.0;
		return (int) Math.round(Math.signum(v) * (3 + 30 * f * f));
	}

	/** R55: the Steam Deck's left trackpad scrolls, up, down and sideways, like a laptop's: the page follows the finger.
	 *  The Steam layout keeps it a d-pad that sends a press only when it is clicked (what Minecraft uses), so a swipe is
	 *  read here from the controller itself, alongside Steam: its raw reports (/dev/hidraw*, Valve 28de:1205), which Steam
	 *  lets this user read. Read only, nothing is sent to it. A report: bytes 0-1 version (1), 2 type (9: the Deck's
	 *  state), 16-17 the left pad's x and 18-19 its y (signed, 0 when untouched). Each movement scrolls by how far the
	 *  finger moved; lifting the finger and putting it down elsewhere doesn't (a jump is ignored). Where the controller
	 *  can't be read (another device, or a sandbox without it), nothing happens. OJ_DECKPAD_FLIP=1 turns the direction. */
	private static void deckPad(Consumer<String> listener) {
		Thread finder = new Thread(() -> {
			List<String> open = new ArrayList<>();
			while (true) {
				try {
					List<Path> devs = new ArrayList<>();
					String only = System.getenv("OJ_DECKPAD_DEV");   // (testing: a file of recorded reports)
					if (only != null) devs.add(Path.of(only));
					else try (java.util.stream.Stream<Path> s = Files.list(Path.of("/sys/class/hidraw"))) {
						s.forEach(h -> { String u = readSmall(h.resolve("device/uevent")); if (u.toUpperCase(java.util.Locale.ROOT).contains("000028DE:00001205")) devs.add(Path.of("/dev", h.getFileName().toString())); });
					} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
					for (Path d : devs) {
						synchronized (open) { if (open.contains(d.toString())) continue; open.add(d.toString()); }
						Thread r = new Thread(() -> { readDeckPad(d, listener); synchronized (open) { open.remove(d.toString()); } }, "deckpad-" + d.getFileName());
						r.setDaemon(true);
						r.start();
					}
					Thread.sleep(only != null ? 600_000 : 5000);
				} catch (InterruptedException e) { return; }
			}
		}, "deckpad-find");
		finder.setDaemon(true);
		finder.start();
	}
	private static String readSmall(Path p) { try { return Files.readString(p); } catch (IOException | RuntimeException e) { return ""; } }
	/** Reads one hidraw device's reports until it goes away, scrolling as the left pad's finger moves. */
	static void readDeckPad(Path d, Consumer<String> listener) {
		boolean flip = "1".equals(System.getenv("OJ_DECKPAD_FLIP")), test = System.getenv("OJ_DECKPAD_DEV") != null;
		int lastX = 0, lastY = 0;
		boolean was = false;
		double carryX = 0, carryY = 0;
		long flushed = 0;
		try (InputStream in = Files.newInputStream(d)) {
			byte[] b = new byte[64];
			while (true) {
				int n = test ? in.readNBytes(b, 0, 64) : in.read(b);
				if (n < 24) { if (n < 0 || test) break; continue; }
				if (b[0] != 1 || b[2] != 9) continue;   // (not the Deck's state report)
				int x = (short) ((b[16] & 0xFF) | (b[17] << 8)), y = (short) ((b[18] & 0xFF) | (b[19] << 8));
				boolean touched = x != 0 || y != 0;
				if (touched && was) {
					int dx = x - lastX, dy = y - lastY;
					if (Math.abs(dx) < 9000 && Math.abs(dy) < 9000) {   // (a jump: the finger was lifted and put down elsewhere)
						// (the page follows the finger: finger up shows what is above; the pad counts up as more)
						carryY += (flip ? dy : -dy) / 45.0;
						carryX += (flip ? dx : -dx) / 45.0;
					}
				}
				lastX = x; lastY = y; was = touched;
				long now = System.currentTimeMillis();
				if (now - flushed >= 25 || test) {
					boolean showing = test;
					for (java.awt.Window w : java.awt.Window.getWindows()) if (w.isShowing()) showing = true;
					int py = (int) carryY, px = (int) carryX;
					if (showing && py != 0) listener.accept("scroll:" + py);
					if (showing && px != 0) listener.accept("hscroll:" + px);
					carryY -= py; carryX -= px;
					flushed = now;
				}
			}
		} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
	}

	/** Linux: one reader for each joystick device there is (and ones that appear later). An event is 8 bytes: time (4),
	 *  value (2, signed), type (1: 1 button, 2 axis; +0x80 for the state sent on opening), number (1). Numbers as an
	 *  Xbox-style pad has them: buttons 0 A, 1 B, 2 X, 3 Y, 6 Back, 7 Start; axes 0 and 1 the left stick, 5 the right
	 *  trigger, 3 and 4 the right stick, 6 and 7 the d-pad. */
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
											else if (n == 3) rightX = value;   // (and left and right: right is more)
											held[LEFT] = stickX[0] || hatX[0]; held[RIGHT] = stickX[1] || hatX[1];
											held[UP] = stickY[0] || hatY[0]; held[DOWN] = stickY[1] || hatY[1];
										}
									}
								}
							} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
							synchronized (held) { java.util.Arrays.fill(held, false); }
							rightY = 0; rightX = 0;
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
