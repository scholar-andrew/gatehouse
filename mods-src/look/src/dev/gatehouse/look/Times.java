package dev.gatehouse.look;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** R100: one rule for time everywhere. Every time that is stored or sent is a unix time: milliseconds since 1970, UTC,
 *  in a field named "at" or ending "_at". Only screens turn it into words, in the viewer's own time zone, through here.
 *  Older records hold a text time in the writing machine's own clock ("2026-11-19 13:25"); {@link #of} reads those as
 *  this machine's clock (right on the machine that wrote them) and {@link #show} shows them as they are elsewhere. */
public final class Times {
	private Times() {}
	private static final DateTimeFormatter OLD = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter LOG = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** Now, as a unix time in milliseconds. */
	public static long now() { return System.currentTimeMillis(); }

	/** A stored unix time (a number, or a number in text); 0 when there is none. */
	public static long num(Object at) {
		if (at instanceof Number n) return n.longValue();
		if (at == null) return 0;
		String s = String.valueOf(at).trim();
		if (!s.matches("\\d{1,16}(\\.\\d*)?")) return 0;
		try { return (long) Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
	}

	/** A record's moment: its unix time when it has one, else its older text time read as this machine's clock
	 *  ("2026-11-19 13:25", with or without seconds, or just the day); 0 when there is neither. */
	public static long of(Object at, Object oldText) {
		long n = num(at);
		return n > 0 ? n : parseOld(oldText == null ? "" : String.valueOf(oldText));
	}

	static long parseOld(String s) {
		s = s.trim();
		try {
			if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			if (s.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?")) {
				String t = s.replace('T', ' ');
				LocalDateTime d = t.length() == 16 ? LocalDateTime.parse(t, OLD) : LocalDateTime.parse(t.substring(0, 19), LOG);
				return d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
			}
			if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?Z")) return Instant.parse(s.length() == 17 ? s.replace("Z", ":00Z") : s).toEpochMilli();
		} catch (RuntimeException e) { return 0; }
		return 0;
	}

	/** A moment in this device's own time: today's as just the time (13:25), this year's with the day (18 Nov, 01:54),
	 *  older ones with the year. "" for none. */
	public static String show(long ms) {
		if (ms <= 0) return "";
		ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
		LocalDate today = LocalDate.now();
		return t.format(DateTimeFormatter.ofPattern(t.toLocalDate().equals(today) ? "HH:mm"
				: t.getYear() == today.getYear() ? "d MMM, HH:mm" : "d MMM yyyy, HH:mm", Locale.ENGLISH));
	}

	/** A record's time for a screen: its unix time in this device's time, else the older text it carries, as it is. */
	public static String show(Object at, Object oldText) {
		long n = num(at);
		return n > 0 ? show(n) : oldText == null ? "" : String.valueOf(oldText);
	}

	/** A moment's time of day to the second, in this device's time (13:25:07), for things looked at every few seconds. */
	public static String clock(long ms) {
		return ms <= 0 ? "" : Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
	}

	/** Just the day of a moment, in this device's time (18 Nov 2026). */
	public static String day(long ms) {
		return ms <= 0 ? "" : Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
	}

	/** The older text form of a moment ("2026-11-19 13:25", this machine's clock), still written beside the unix time so
	 *  versions from before R100 have something to show. */
	public static String oldText(long ms) {
		return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(OLD);
	}

	/** The start of a log line: the unix time, then the same moment in this machine's time for reading. */
	public static String logStamp() {
		long ms = now();
		return ms + " " + Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(LOG);
	}
}
