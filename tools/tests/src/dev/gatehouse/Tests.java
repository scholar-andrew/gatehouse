package dev.gatehouse;

/** A tiny checker for run_tests.ps1: each check prints "  ok" or "  FAILED", and done() ends with exit code 1 if any failed. */
public final class Tests {
	private final String what;
	private int failed;
	public Tests(String what) { this.what = what; System.out.println(what + ":"); }
	public void check(String name, boolean ok) { System.out.println((ok ? "  ok      " : "  FAILED  ") + name); if (!ok) failed++; }
	public void done() { System.out.println(failed == 0 ? "PASS " + what : "FAIL " + what + " (" + failed + ")"); System.exit(failed == 0 ? 0 : 1); }
}
