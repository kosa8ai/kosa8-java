package com.kosa8;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON codec so the SDK has no dependencies. Objects decode to
 * {@code Map<String,Object>}, arrays to {@code List<Object>}, integers to
 * {@code Long}, other numbers to {@code Double}.
 */
public final class Json {
  private Json() {}

  /** Parses JSON text. */
  public static Object parse(String s) {
    Parser p = new Parser(s);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != s.length()) throw new IllegalArgumentException("trailing data at " + p.i);
    return v;
  }

  /** Writes a value (Map, Iterable, array, String, Number, Boolean, null) as JSON. */
  public static String write(Object v) {
    StringBuilder b = new StringBuilder();
    write(b, v);
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private static void write(StringBuilder b, Object v) {
    if (v == null) {
      b.append("null");
    } else if (v instanceof String s) {
      quote(b, s);
    } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short) {
      b.append(v);
    } else if (v instanceof Number n) {
      double d = n.doubleValue();
      if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
      else b.append(d);
    } else if (v instanceof Map<?, ?> m) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) b.append(',');
        first = false;
        quote(b, String.valueOf(e.getKey()));
        b.append(':');
        write(b, e.getValue());
      }
      b.append('}');
    } else if (v instanceof Iterable<?> it) {
      b.append('[');
      boolean first = true;
      for (Object o : it) {
        if (!first) b.append(',');
        first = false;
        write(b, o);
      }
      b.append(']');
    } else if (v instanceof Object[] arr) {
      write(b, List.of(arr));
    } else {
      quote(b, v.toString());
    }
  }

  private static void quote(StringBuilder b, String s) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    b.append('"');
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) {
      this.s = s;
    }

    void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    IllegalArgumentException err(String what) {
      return new IllegalArgumentException("bad JSON: " + what + " at " + i);
    }

    Object value() {
      if (i >= s.length()) throw err("unexpected end");
      char c = s.charAt(i);
      switch (c) {
        case '{':
          return object();
        case '[':
          return array();
        case '"':
          return string();
        case 't':
          return lit("true", Boolean.TRUE);
        case 'f':
          return lit("false", Boolean.FALSE);
        case 'n':
          return lit("null", null);
        default:
          return number();
      }
    }

    Object lit(String word, Object v) {
      if (!s.startsWith(word, i)) throw err("expected " + word);
      i += word.length();
      return v;
    }

    Map<String, Object> object() {
      Map<String, Object> m = new LinkedHashMap<>();
      i++;
      ws();
      if (i < s.length() && s.charAt(i) == '}') {
        i++;
        return m;
      }
      while (true) {
        ws();
        if (i >= s.length() || s.charAt(i) != '"') throw err("expected key");
        String k = string();
        ws();
        if (i >= s.length() || s.charAt(i) != ':') throw err("expected :");
        i++;
        ws();
        m.put(k, value());
        ws();
        if (i >= s.length()) throw err("unterminated object");
        char c = s.charAt(i++);
        if (c == '}') return m;
        if (c != ',') throw err("expected , or }");
      }
    }

    List<Object> array() {
      List<Object> l = new ArrayList<>();
      i++;
      ws();
      if (i < s.length() && s.charAt(i) == ']') {
        i++;
        return l;
      }
      while (true) {
        ws();
        l.add(value());
        ws();
        if (i >= s.length()) throw err("unterminated array");
        char c = s.charAt(i++);
        if (c == ']') return l;
        if (c != ',') throw err("expected , or ]");
      }
    }

    String string() {
      StringBuilder b = new StringBuilder();
      i++;
      while (true) {
        if (i >= s.length()) throw err("unterminated string");
        char c = s.charAt(i++);
        if (c == '"') return b.toString();
        if (c != '\\') {
          b.append(c);
          continue;
        }
        char e = s.charAt(i++);
        switch (e) {
          case 'n' -> b.append('\n');
          case 't' -> b.append('\t');
          case 'r' -> b.append('\r');
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'u' -> {
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> b.append(e);
        }
      }
    }

    Object number() {
      int st = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
      String n = s.substring(st, i);
      if (n.isEmpty()) throw err("unexpected character");
      if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
        try {
          return Long.parseLong(n);
        } catch (NumberFormatException ignored) {
          // too big for a long
        }
      }
      return Double.parseDouble(n);
    }
  }
}
