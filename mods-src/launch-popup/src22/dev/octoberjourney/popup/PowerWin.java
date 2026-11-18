package dev.octoberjourney.popup;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** R42, Windows: this computer's battery, from Windows' own GetSystemPowerStatus, through Java's foreign-function support
 *  (built for Java 22, as PadWin is; PackShare loads it by name and says nothing about a battery on an older Java). In
 *  words, as the web panel's Systems page shows it: "82%, charging", "45%", or "" for a computer with no battery. It
 *  only reads. */
public final class PowerWin implements java.util.function.Supplier<String> {
	private final MethodHandle get;
	private final MemorySegment status;

	public PowerWin() {
		SymbolLookup k = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
		get = Linker.nativeLinker().downcallHandle(k.find("GetSystemPowerStatus").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		// SYSTEM_POWER_STATUS: AC line status, battery flag, battery percent, system status flag (1 byte each), then two DWORDs.
		status = Arena.global().allocate(12, 4);
	}

	@Override public String get() {
		try {
			if ((int) get.invokeExact(status) == 0) return "";
			int ac = status.get(ValueLayout.JAVA_BYTE, 0) & 0xFF, flag = status.get(ValueLayout.JAVA_BYTE, 1) & 0xFF,
					pct = status.get(ValueLayout.JAVA_BYTE, 2) & 0xFF;
			if (flag == 255 || (flag & 128) != 0 || pct > 100) return "";   // (unknown, or no battery: a desktop)
			return pct + "%" + ((flag & 8) != 0 ? ", charging" : ac == 1 ? (pct >= 99 ? ", full" : ", plugged in") : "");
		} catch (Throwable t) { return ""; }
	}
}
