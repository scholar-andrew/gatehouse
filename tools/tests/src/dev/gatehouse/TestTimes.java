package dev.gatehouse;

import dev.gatehouse.look.Times;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** R100: every stored or sent time is a unix time (milliseconds since 1970, UTC); only screens turn it into words.
 *  Checks the shared helper, then the program's sources: a time read from the machine's own clock may only become a
 *  file name, a day label, a fallback text beside a unix time, or a clock time the owner set (allowed below). */
public final class TestTimes {
	/** file : a piece of the line, for the uses of the machine's clock that are meant (what each one is for is said). */
	private static final String[][] ALLOWED = {
		{"HostHelper.java", "\"checked\", java.time.LocalTime.now()"},       // text beside checked_at, for older versions
		{"HostHelper.java", "\"time\", java.time.LocalTime.now()"},          // text beside "at", for older versions
		{"HostHelper.java", "String at = LocalDateTime.now()"},              // shown in this machine's own window at once
		{"McssHost.java", "String name = \"Gatekeeper \""},                  // an MCSS key's name
		{"Packs.java", "\"date\", LocalDate.now().toString()"},              // a version's day label, beside "at"
		{"SafeFile.java", "String when = "},                                 // a safety copy's file name
		{"ServerTools.java", "eula=true"},                                   // Minecraft's own eula.txt comment line
		{"ServerTools.java", "\"backup_\" + LocalDateTime.now()"},          // a backup's file name
		{"ServerTools.java", "String hhmm = "},                              // daily tasks run at the owner's clock time
		{"StatusPage.java", "String when = java.time.LocalTime.now()"},      // text the page shows without script
		{"ChatView.java", "HH.mm.ss\")) + \".png\""},                       // a saved picture's file name
		{"Times.java", "LocalDate today = LocalDate.now()"},                 // the helper itself
	};

	public static void main(String[] a) throws Exception {
		Tests t = new Tests("unix times");
		TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"));
		long ms = 1795526721304L;   // 2026-11-24 13:25:21.304 UTC = 06:25:21 in Denver
		t.check("a unix time is read as it is", Times.num(ms) == ms && Times.num("1795526721304") == ms && Times.of(ms, "1999-01-01 00:00") == ms);
		t.check("nothing is 0", Times.num(null) == 0 && Times.num("") == 0 && Times.num("2026-11-19 13:25") == 0 && Times.of(null, null) == 0);
		t.check("an older text time is read as this machine's clock", Times.of(null, "2026-11-24 06:25") == 1795526700000L);
		t.check("with seconds too", Times.of("", "2026-11-24 06:25:21") == 1795526721000L);
		t.check("a UTC text time is read as UTC", Times.of(null, "2026-11-24T13:25:21.304Z") == ms);
		t.check("a day alone is its start here", Times.of(null, "2026-11-24") == 1795503600000L);
		t.check("rubbish is no time", Times.of(null, "soon") == 0 && Times.of(null, "2026-13-45 99:99") == 0);
		t.check("the older text form is this machine's clock", Times.oldText(ms).equals("2026-11-24 06:25"));
		t.check("a screen shows it in this device's own time", Times.show(ms).equals("24 Nov 2026, 06:25") || Times.show(ms).equals("24 Nov, 06:25") || Times.show(ms).equals("06:25"));
		t.check("today's is just the time", Times.show(Times.now()).matches("\\d{2}:\\d{2}"));
		t.check("an older record shows its text as it is", Times.show(null, "2026-11-19 13:25").equals("2026-11-19 13:25"));
		t.check("to the second", Times.clock(ms).equals("06:25:21"));
		t.check("a log line starts with the unix time", Times.logStamp().matches("\\d{13} \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
		t.check("the same moment in another time zone", Times.clock(ms).equals("22:25:21"));

		// the sources: the machine's clock turned into text only where it is meant to be
		Path root = Path.of(a.length > 0 ? a[0] : ".");
		List<String> found = new ArrayList<>();
		for (String dir : new String[] {"host/src", "mods-src"}) {
			try (var files = Files.walk(root.resolve(dir))) {
				for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
					List<String> lines = Files.readAllLines(p);
					for (int i = 0; i < lines.size(); i++) {
						String l = lines.get(i);
						if (!l.matches(".*Local(Date)?(Time)?\\.now\\(\\).*") && !l.contains(".format(TIME)")) continue;
						String name = p.getFileName().toString();
						boolean ok = false;
						for (String[] w : ALLOWED) if (name.equals(w[0]) && l.contains(w[1])) ok = true;
						if (!ok) found.add(name + ":" + (i + 1) + "  " + l.trim());
					}
				}
			}
		}
		for (String f : found) System.out.println("    the machine's clock as text: " + f);
		t.check("no new code stores the machine's clock as text (" + found.size() + " found)", found.isEmpty());
		t.done();
	}
}
