package dev.octoberjourney.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader and writer (Java has none built in): objects, arrays, strings, true/false/null and numbers. */
final class Json {
	private final String s;
	private int i;
	private int depth;
	/** R36: how deep objects and lists may nest. Nothing Gatehouse sends comes near it; much deeper would only be an
	 *  attempt to exhaust the reader's stack. */
	private static final int MAX_DEPTH = 64;
	private Json(String s) { this.s = s; }

	static Object parse(String s) {
		try { return s == null || s.isBlank() ? null : new Json(s).value(); } catch (RuntimeException e) { return null; }
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }
	@SuppressWarnings("unchecked")
	static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }
	static String str(Object o) { return o == null ? "" : o.toString(); }

	private Object value() {
		ws();
		char c = s.charAt(i);
		if ((c == '{' || c == '[') && depth >= MAX_DEPTH) throw new IllegalArgumentException("nested too deeply");
		if (c == '{' || c == '[') { depth++; try { return nested(c); } finally { depth--; } }
		if (c == '"') return str();
		int st = i;
		while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
		String w = s.substring(st, i);
		return w.equals("true") ? Boolean.TRUE : w.equals("false") ? Boolean.FALSE : w.equals("null") ? null : w;
	}
	private Object nested(char c) {
		if (c == '{') {
			i++;
			Map<String, Object> m = new LinkedHashMap<>();
			ws();
			if (s.charAt(i) == '}') { i++; return m; }
			while (true) {
				ws(); String k = str(); ws(); i++;
				m.put(k, value()); ws();
				if (s.charAt(i++) == '}') return m;
			}
		}
		if (c == '[') {
			i++;
			List<Object> l = new ArrayList<>();
			ws();
			if (s.charAt(i) == ']') { i++; return l; }
			while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; }
		}
		return null;
	}

	private String str() {
		i++;
		StringBuilder b = new StringBuilder();
		while (true) {
			char c = s.charAt(i++);
			if (c == '"') return b.toString();
			if (c != '\\') { b.append(c); continue; }
			char e = s.charAt(i++);
			switch (e) {
				case 'n' -> b.append('\n');
				case 't' -> b.append('\t');
				case 'r' -> b.append('\r');
				case 'b' -> b.append('\b');
				case 'f' -> b.append('\f');
				case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
				default -> b.append(e);
			}
		}
	}

	private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

	static String write(Object o) {
		StringBuilder b = new StringBuilder();
		write(o, b, "");
		return b.toString();
	}

	private static void write(Object o, StringBuilder b, String ind) {
		if (o instanceof Map<?, ?> m) {
			if (m.isEmpty()) { b.append("{}"); return; }
			b.append("{\n");
			int n = 0;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				b.append(ind).append(" ");
				quote(String.valueOf(e.getKey()), b);
				b.append(": ");
				write(e.getValue(), b, ind + " ");
				b.append(++n < m.size() ? ",\n" : "\n");
			}
			b.append(ind).append("}");
		} else if (o instanceof List<?> l) {
			b.append("[");
			for (int k = 0; k < l.size(); k++) { if (k > 0) b.append(", "); write(l.get(k), b, ind); }
			b.append("]");
		} else if (o == null) b.append("null");
		else if (o instanceof Boolean || o instanceof Number) b.append(o);
		else quote(o.toString(), b);
	}

	private static void quote(String s, StringBuilder b) {
		b.append('"');
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
			}
		}
		b.append('"');
	}
}
