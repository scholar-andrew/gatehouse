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
 *  and carries on without a controller if this Java is older or XInput isn't there. It only reads. */
public final class PadWin implements java.util.function.Supplier<int[]> {
	private final MethodHandle getState;
	private final MemorySegment state;

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
		return null;
	}
}
