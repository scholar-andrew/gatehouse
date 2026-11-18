package dev.gatehouse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Release signatures (R24), with a throwaway key that run_tests.ps1 makes and puts first on the class path in place
 *  of the release key: a good file passes, a changed one is refused, a missing signature counts as unsigned, and a
 *  checked signature is kept. args: work folder, the sample file's SHA-256, the address the signatures are served at. */
public class TestSigned {
	public static void main(String[] a) throws Exception {
		Path d = Path.of(a[0]);
		byte[] jar = Files.readAllBytes(d.resolve("sample.jar"));
		String sha = a[1], at = a[2];
		Tests t = new Tests("signatures");
		t.check("the key loads", Signed.key() != null);
		t.check("a good file is signed", Signed.check(jar, sha, List.of(at), null).equals("signed"));
		byte[] changed = jar.clone(); changed[changed.length / 2] ^= 1;
		t.check("a changed file is bad", Signed.check(changed, sha, List.of(at), null).equals("bad"));
		t.check("no signature is unsigned", Signed.check(jar, "00" + sha.substring(2), List.of(at), null).equals("unsigned"));
		Path keep = d.resolve("kept");
		t.check("a checked one is kept", Signed.check(jar, sha, List.of(at), keep).equals("signed") && Files.exists(keep.resolve(sha + ".sig")));
		t.done();
	}
}
