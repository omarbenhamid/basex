package org.basex.query.expr;

import static org.basex.query.QueryTokens.*;
import java.io.IOException;
import org.basex.data.Serializer;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.item.Bln;
import org.basex.query.item.SeqType;
import org.basex.query.iter.Iter;
import org.basex.query.util.Var;
import org.basex.util.InputInfo;
import org.basex.util.Token;

/**
 * Some/Every satisfier clause.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-10, ISC License
 * @author Christian Gruen
 */
public final class Quantifier extends ParseExpr {
  /** Every flag. */
  private final boolean every;
  /** For/Let expressions. */
  private For[] fl;
  /** Expression list. */
  private Expr sat;

  /**
   * Constructor.
   * @param ii input info
   * @param f variable inputs
   * @param s satisfier
   * @param e every flag
   */
  public Quantifier(final InputInfo ii, final For[] f, final Expr s,
      final boolean e) {
    super(ii);
    sat = s;
    fl = f;
    every = e;
    type = SeqType.BLN;
  }

  @Override
  public Expr comp(final QueryContext ctx) throws QueryException {
    // compile for clauses
    final int vs = ctx.vars.size();
    for(final For f : fl) f.comp(ctx);
    sat = checkUp(sat, ctx).comp(ctx).compEbv(ctx);
    ctx.vars.reset(vs);

    // find empty sequences
    boolean empty = sat.empty();
    for(final For f : fl) empty |= f.empty();

    // return pre-evaluated result
    return empty ? optPre(Bln.get(every), ctx) : this;
  }

  @Override
  public Bln atomic(final QueryContext ctx, final InputInfo ii)
      throws QueryException {

    final Iter[] iter = new Iter[fl.length];
    for(int f = 0; f < fl.length; ++f) iter[f] = ctx.iter(fl[f]);
    return Bln.get(iter(ctx, iter, 0));
  }

  /**
   * Performs a recursive iteration on the specified variable position.
   * @param ctx root reference
   * @param it iterator
   * @param p variable position
   * @return satisfied flag
   * @throws QueryException query exception
   */
  private boolean iter(final QueryContext ctx, final Iter[] it,
      final int p) throws QueryException {

    final boolean last = p + 1 == fl.length;
    while(it[p].next() != null) {
      if(every ^ (last ? sat.ebv(ctx, input).bool(input) :
        iter(ctx, it, p + 1))) {
        for(final Iter ri : it) ri.reset();
        return !every;
      }
    }
    return every;
  }

  @Override
  public boolean uses(final Use u) {
    return u == Use.VAR || sat.uses(u);
  }

  @Override
  public boolean removable(final Var v) {
    for(final ForLet f : fl) {
      if(!f.removable(v)) return false;
      if(f.shadows(v)) return true;
    }
    return sat.removable(v);
  }

  @Override
  public Expr remove(final Var v) {
    for(final ForLet f : fl) {
      f.remove(v);
      if(f.shadows(v)) return this;
    }
    sat = sat.remove(v);
    return this;
  }

  @Override
  public void plan(final Serializer ser) throws IOException {
    ser.openElement(this, TYPE, Token.token(every ? EVERY : SOME));
    for(final Expr f : fl) f.plan(ser);
    sat.plan(ser);
    ser.closeElement();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(every ? EVERY : SOME);
    for(final For f : fl) sb.append(" " + f);
    return sb.append(" " + SATISFIES + " " + sat).toString();
  }
}
