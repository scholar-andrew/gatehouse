package dev.gatehouse.look;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Method;

/**
 * R80: the process's own environment, as native libraries see it (GStreamer's sound output reads PIPEWIRE_LATENCY and
 * PULSE_LATENCY_MSEC from it when it starts). Java can't change its environment, and System.getenv shows only what the
 * process started with; this calls the C library's setenv and getenv instead, through Java's foreign function
 * interface (Java 22 and newer; reached by name, since the program is built for Java 17). On Windows, on older Java,
 * or if anything fails, it does nothing and says so.
 */
public final class NativeEnv {
	private NativeEnv() {}

	/** Sets name=value when it isn't set yet. True when set (or already set); false when it couldn't be. */
	public static boolean setIfUnset(String name, String value) {
		String now = get(name);
		if (now != null && !now.isEmpty()) return true;
		Object r = call("setenv", true, name, value);
		return r instanceof Integer i && i == 0;
	}
	/** The value native code sees, null when unset, or "?" when it can't be read. */
	public static String get(String name) {
		Object r = call("getenv", false, name, null);
		return r instanceof String s ? s : r == null ? null : "?";
	}

	private static Object call(String fn, boolean set, String name, String value) {
		if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) return "?";
		try {
			Class<?> linkerC = Class.forName("java.lang.foreign.Linker"), arenaC = Class.forName("java.lang.foreign.Arena"),
					segC = Class.forName("java.lang.foreign.MemorySegment"), layoutC = Class.forName("java.lang.foreign.MemoryLayout"),
					valueC = Class.forName("java.lang.foreign.ValueLayout"), fdC = Class.forName("java.lang.foreign.FunctionDescriptor"),
					optC = Class.forName("java.lang.foreign.Linker$Option"), lookupC = Class.forName("java.lang.foreign.SymbolLookup");
			Object linker = linkerC.getMethod("nativeLinker").invoke(null);
			Object lookup = linkerC.getMethod("defaultLookup").invoke(linker);
			java.util.Optional<?> found = (java.util.Optional<?>) lookupC.getMethod("find", String.class).invoke(lookup, fn);
			if (found.isEmpty()) return "?";
			Object address = valueC.getField("ADDRESS").get(null), jint = valueC.getField("JAVA_INT").get(null);
			Object args = Array.newInstance(layoutC, set ? 3 : 1);
			Array.set(args, 0, address);
			if (set) { Array.set(args, 1, address); Array.set(args, 2, jint); }
			Object fd = fdC.getMethod("of", layoutC, args.getClass()).invoke(null, set ? jint : address, args);
			MethodHandle h = (MethodHandle) linkerC.getMethod("downcallHandle", segC, fdC, Array.newInstance(optC, 0).getClass())
					.invoke(linker, found.get(), fd, Array.newInstance(optC, 0));
			Object arena = arenaC.getMethod("ofConfined").invoke(null);
			try {
				Method alloc = arenaC.getMethod("allocateFrom", String.class);
				Object n = alloc.invoke(arena, name);
				if (set) return h.invokeWithArguments(n, alloc.invoke(arena, value), 0);
				Object seg = h.invokeWithArguments(n);
				if ((long) segC.getMethod("address").invoke(seg) == 0) return null;
				Object wide = segC.getMethod("reinterpret", long.class).invoke(seg, 4096L);
				return segC.getMethod("getString", long.class).invoke(wide, 0L);
			} finally { arenaC.getMethod("close").invoke(arena); }
		} catch (Throwable e) { Quiet.seen(e); return "?"; }
	}
}
