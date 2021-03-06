package org.basex.io.serial.json;

import static org.basex.io.parse.json.JsonConstants.*;
import static org.basex.query.QueryError.*;
import static org.basex.util.Token.*;

import java.io.*;

import org.basex.io.parse.json.*;
import org.basex.io.serial.*;
import org.basex.query.*;
import org.basex.query.iter.*;
import org.basex.query.util.ft.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.query.value.type.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * This class serializes items as JSON. The input must conform to the rules
 * defined in the {@link JsonDirectConverter} and {@link JsonAttsConverter} class.
 *
 * @author BaseX Team 2005-16, BSD License
 * @author Christian Gruen
 */
public final class JsonBasicSerializer extends JsonSerializer {
  /** Output key (false for arrays). */
  private boolean printKey;
  /** Printed keys. */
  private TokenSet printedKeys = new TokenSet();

  /**
   * Constructor.
   * @param os output stream
   * @param opts serialization parameters
   * @throws IOException I/O exception
   */
  public JsonBasicSerializer(final OutputStream os, final SerializerOptions opts)
      throws IOException {
    super(os, opts);
  }

  @Override
  protected void node(final ANode node) throws IOException {
    if(level > 0) indent();

    final BasicNodeIter iter = node.children();
    if(node.type == NodeType.DOC || node.type == NodeType.DEL) {
      for(ANode child; (child = iter.next()) != null;) node(child);
    } else if(node.type == NodeType.ELM) {
      final QNm name = node.qname();
      final byte[] type = name.local();
      if(!eq(name.uri(), QueryText.FN_URI))
        throw error("Element '%' has invalid namespace: '%'.", type, name.uri());

      byte[] key = null;
      boolean escaped = false, escapedKey = false;
      for(final ANode attr : node.attributes()) {
        final QNm qnm = attr.qname();
        final byte[] au = qnm.uri(), an = qnm.local(), av = attr.string();
        if(au.length != 0) {
          if(!eq(au, QueryText.FN_URI)) continue;
          throw error("Element '%' has invalid attribute: %.", type, an);
        }
        if(eq(an, KEY)) {
          key = attr.string();
        } else if(eq(an, ESCAPED_KEY) && printKey) {
          final Boolean b = Bln.parse(av);
          if(b == null) throw error("Value of '%' attribute is invalid: '%'.", an, av);
          escapedKey = b;
        } else if(eq(an, ESCAPED) && eq(type, STRING)) {
          final Boolean b = Bln.parse(av);
          if(b == null) throw error("Value of '%' attribute is invalid: '%'.", an, av);
          escaped = b;
        } else {
          throw error("Element '%' has invalid attribute: %.", type, an);
        }
      }

      if(printKey) {
        if(key == null) throw error("Element '%' has no key.", type);
        key = escape(key, escapedKey);
        if(!printedKeys.add(key)) throw error("Duplicate key: %.", key);
        out.print('"');
        out.print(norm(key));
        out.print("\":");
      } else {
        if(key != null) throw error("Element '%' must have no key.", type);
      }

      if(eq(type, NULL)) {
        out.print(NULL);
        for(ANode n; (n = iter.next()) != null;) {
          if(n.type != NodeType.COM && n.type != NodeType.PI)
            throw error("Element '%' must have no children.", type);
        }
      } else if(eq(type, BOOLEAN)) {
        final byte[] value = value(iter, type);
        if(value == null) throw error("Element '%' has no value.", type);
        final Boolean b = Bln.parse(value);
        if(b == null) throw error("Element '%' has invalid value: '%'.", type, value);
        out.print(norm(token(b.booleanValue())));
      } else if(eq(type, STRING)) {
        final byte[] value = value(iter, type);
        out.print('"');
        if(value != null) out.print(norm(escape(value, escaped)));
        out.print('"');
      } else if(eq(type, NUMBER)) {
        final byte[] value = value(iter, type);
        if(value == null) throw error("Element '%' has no value.", type);
        final double d = toDouble(value);
        if(Double.isNaN(d) || Double.isInfinite(d))
          throw error("Element '%' has invalid value: '%'.", type, value);
        out.print(token(d));
      } else if(eq(type, ARRAY)) {
        out.print('[');
        children(iter, false);
        out.print(']');
      } else if(eq(type, MAP)) {
        out.print('{');
        children(iter, true);
        out.print('}');
      } else {
        throw error("Invalid element: '%'", name);
      }
    //} else {
    //  throw error("Node must be an element.");
    }
  }

  @Override
  protected void startOpen(final QNm name) {
    throw Util.notExpected();
  }

  @Override
  protected void attribute(final byte[] name, final byte[] value, final boolean standalone) {
    throw Util.notExpected();
  }

  @Override
  protected void finishOpen() {
    throw Util.notExpected();
  }

  @Override
  protected void text(final byte[] value, final FTPos ftp) {
    throw Util.notExpected();
  }

  @Override
  protected void finishEmpty() {
    throw Util.notExpected();
  }

  @Override
  protected void finishClose() {
    throw Util.notExpected();
  }

  @Override
  protected void atomic(final Item value) throws IOException {
    throw BXJS_SERIAL_X.getIO("Atomic values cannot be serialized");
  }

  /**
   * Serializes child nodes.
   * @param iter iterator
   * @param pk print keys
   * @throws IOException I/O exception
   */
  private void children(final BasicNodeIter iter, final boolean pk) throws IOException {
    final boolean p = printKey;
    final TokenSet keys = printedKeys;
    printKey = pk;
    printedKeys = new TokenSet();
    level++;
    boolean comma = false;
    for(ANode child; (child = iter.next()) != null;) {
      if(child.type == NodeType.ELM) {
        if(comma) out.print(',');
        node(child);
        comma = true;
      } else if(child.type == NodeType.TXT && !ws(child.string())) {
        throw error("Element '%' must have no text nodes.", child.name());
      }
    }
    level--;
    indent();
    printKey = p;
    printedKeys = keys;
  }

  /**
   * Returns the value of a node.
   * @param iter iterator
   * @param type type
   * @return value
   * @throws QueryIOException query exception
   */
  private static byte[] value(final BasicNodeIter iter, final byte[] type) throws QueryIOException {
    TokenBuilder tb = null;
    for(ANode child; (child = iter.next()) != null;) {
      if(child.type == NodeType.TXT) {
        if(tb == null) tb = new TokenBuilder();
        tb.add(child.string());
      } else if(child.type == NodeType.ELM) {
        throw error("Element '%' must have no child elements.", type);
      }
    }
    return tb == null ? null : tb.finish();
  }

  /**
   * Returns a possibly escaped value.
   * @param value value to escape
   * @param escape escape flag
   * @return escaped value
   * @throws QueryIOException I/O exception
   */
  private static byte[] escape(final byte[] value, final boolean escape) throws QueryIOException {
    if(escape) {
      if(contains(value, '\\')) {
        final TokenParser tp = new TokenParser(value);
        while(tp.more()) {
          int c = tp.next();
          if(c == '\\') {
            if(!tp.more()) throw JSON_ESCAPE_X.getIO(value);
            c = tp.next();
            if(indexOf(ESCAPES, c) == -1) throw JSON_ESCAPE_X.getIO(value);
            if(c == 'u') {
              for(int i = 0; i < 4; i++) {
                if(!tp.more()) throw JSON_ESCAPE_X.getIO(value);
                c = tp.next();
                if(c < '0' || c > '9' && c < 'A' || c > 'F' && c < 'a' || c > 'f')
                  throw JSON_ESCAPE_X.getIO(value);
              }
            }
          }
        }
      }
    }

    final TokenBuilder tb = new TokenBuilder();
    boolean bs = false;
    final int vl = value.length;
    for(int v = 0; v < vl; v += cl(value, v)) {
      final int cp = cp(value, v);
      if(cp >= 0 && cp < 32 || cp >= 127 && cp < 160) {
        tb.add('\\');
        if(cp == '\b') tb.add('b');
        else if(cp == '\f') tb.add('f');
        else if(cp == '\n') tb.add('n');
        else if(cp == '\r') tb.add('r');
        else if(cp == '\t') tb.add('t');
        else tb.add('u').add('0').add('0').add(HEX[cp >> 4]).add(HEX[cp & 0xF]);
      } else {
        if(cp == '"' && !bs || !escape && cp == '\\') {
          bs = true;
          tb.add('\\');
        }
        tb.add(cp);
      }
      bs = !bs && cp == '\\';
    }
    return tb.finish();
  }

  /**
   * Raises an error with the specified message.
   * @param msg error message
   * @param ext error details
   * @return I/O exception
   */
  private static QueryIOException error(final String msg, final Object... ext) {
    return JSON_INVALID_X.getIO(Util.inf(msg, ext));
  }
}
