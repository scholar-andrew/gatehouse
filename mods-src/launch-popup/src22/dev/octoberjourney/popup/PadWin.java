package dev.octoberjourney.popup;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Windows: the state of the first connected controller, from XInput (the same thing games read), through Java's own
 *  foreign-function support. This one class is built for Java 22, where that support is final; Pad loads it by name
 *  and carries on without a controller if this Java is older or XInput isn't there. It only reads.
 *
 *  R56: XInput only sees Xbox-style controllers. A PlayStation or Switch controller, or another gamepad, becomes one
 *  only when Steam starts the program; opened straight from the desktop it isn't there. So when XInput has no
 *  controller, the first one Windows' own joystick interface (winmm, which every gamepad driver offers) has is read
 *  instead, and its buttons are put where an Xbox controller has them: by maker, Sony (DualShock, DualSense), Nintendo
 *  (Switch Pro, by the letters printed on it), else the usual order. Never both at once, so nothing is pressed twice. */
public final class PadWin implements java.util.function.Supplier<int[]> {
	private final MethodHandle getState;
	private final MemorySegment state;
	private MethodHandle joyPos, joyCaps;
	private MemorySegment info, caps;
	private int joyId = -1, joyVendor;
	private long joyLooked;

	public PadWin() {
		SymbolLookup lib = null;
		for (String n : new String[] {"xinput1_4.dll", "xinput1_3.dll", "xinput9_1_0.dll"}) {
			try { lib = SymbolLookup.libraryLookup(n, Arena.global()); break; } catch (IllegalArgumentException e) { /* the next one */ }
		}
		if (lib == null) throw new IllegalStateException("no XInput on this computer");
		getState = Linker.nativeLinker().downcallHandle(lib.find("XInputGetState").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		// XINPUT_STATE: packet number (4 bytes), then the pad: buttons (2), left trigger (1), right trigger (1), left stick x, y, right stick x, y (2 each).
		state = Arena.global().allocate(16, 4);
		try {
			SymbolLookup mm = SymbolLookup.libraryLookup("winmm.dll", Arena.global());
			joyPos = Linker.nativeLinker().downcallHandle(mm.find("joyGetPosEx").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			joyCaps = Linker.nativeLinker().downcallHandle(mm.find("joyGetDevCapsW").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
			info = Arena.global().allocate(52, 4);   // (JOYINFOEX: 13 four-byte fields)
			caps = Arena.global().allocate(728, 4);   // (JOYCAPSW: the maker and product ids first)
		} catch (RuntimeException e) { joyPos = null; }
	}

	/** {buttons, right trigger 0-255, left stick x, left stick y, right stick x, right stick y}; null when no controller is connected. */
	@Override public int[] get() {
		try {
			for (int i = 0; i < 4; i++) {
				int r = (int) getState.invokeExact(i, state);
				if (r != 0) continue;
				return new int[] {state.get(ValueLayout.JAVA_SHORT, 4) & 0xFFFF, state.get(ValueLayout.JAVA_BYTE, 7) & 0xFF,
						state.get(ValueLayout.JAVA_SHORT, 8), state.get(ValueLayout.JAVA_SHORT, 10), state.get(ValueLayout.JAVA_SHORT, 12),
						state.get(ValueLayout.JAVA_SHORT, 14)};
			}
		} catch (Throwable t) { /* no controller this time */ }
		return joystick();
	}

	/** The first winmm controller, as XInput would give it; null when there is none. */
	private int[] joystick() {
		if (joyPos == null) return null;
		try {
			if (joyId < 0) {   // (looked for again every few seconds while there is none: a missing one is slow to ask)
				long now = System.currentTimeMillis();
				if (now - joyLooked < 3000) return null;
				joyLooked = now;
				for (int id = 0; id < 16 && joyId < 0; id++) if (pos(id)) {
					joyId = id;
					joyVendor = (int) joyCaps.invokeExact((long) id, caps, 728) == 0 ? caps.get(ValueLayout.JAVA_SHORT, 0) & 0xFFFF : 0;
				}
				if (joyId < 0) return null;
			}
			if (!pos(joyId)) { joyId = -1; return null; }
			return translate(joyVendor, info.get(ValueLayout.JAVA_INT, 32), info.get(ValueLayout.JAVA_INT, 40), info.get(ValueLayout.JAVA_INT, 8),
					info.get(ValueLayout.JAVA_INT, 12), info.get(ValueLayout.JAVA_INT, 16), info.get(ValueLayout.JAVA_INT, 20));
		} catch (Throwable t) { joyId = -1; return null; }
	}
	private boolean pos(int id) throws Throwable {
		info.set(ValueLayout.JAVA_INT, 0, 52);    // dwSize
		info.set(ValueLayout.JAVA_INT, 4, 0xFF);  // dwFlags: JOY_RETURNALL
		return (int) joyPos.invokeExact(id, info) == 0;
	}

	static final int UP = 0x1, DOWN = 0x2, LEFT = 0x4, RIGHT = 0x8, START = 0x10, BACK = 0x20, LB = 0x100, RB = 0x200, A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;
	/** A winmm controller's reading, in XInput's terms. buttons: one bit per button (button 1 is bit 0); pov: hundredths
	 *  of a degree clockwise from up, or 65535 when centred; axes 0-65535 with y counting down. Public for the tests. */
	public static int[] translate(int vendor, int buttons, int pov, int x, int y, int z, int r) {
		// which winmm button is A, B, X, Y, LB, RB, Back, Start, and the right trigger, for this maker
		int[] at = vendor == 0x054C ? new int[] {1, 2, 0, 3, 4, 5, 8, 9, 7}   // Sony: Cross, Circle, Square, Triangle, L1, R1, Share/Create, Options, R2
				: vendor == 0x057E ? new int[] {1, 0, 3, 2, 4, 5, 8, 9, 7}       // Nintendo: A, B, X, Y, L, R, minus, plus, ZR (the letters as printed)
				: new int[] {0, 1, 2, 3, 4, 5, 6, 7, -1};                          // others: the usual order
		int[] bit = {A, B, X, Y, LB, RB, BACK, START};
		int out = 0;
		for (int i = 0; i < bit.length; i++) if ((buttons >> at[i] & 1) != 0) out |= bit[i];
		if (pov >= 0 && pov < 36000) {
			if (pov >= 31500 || pov <= 4500) out |= UP;
			if (pov >= 4500 && pov <= 13500) out |= RIGHT;
			if (pov >= 13500 && pov <= 22500) out |= DOWN;
			if (pov >= 22500 && pov <= 31500) out |= LEFT;
		}
		int rt = at[8] >= 0 && (buttons >> at[8] & 1) != 0 ? 255 : 0;
		return new int[] {out, rt, axis(x), -axis(y), axis(z), -axis(r)};
	}
	private static int axis(int v) { return Math.max(-32768, Math.min(32767, v - 32768)); }
}
