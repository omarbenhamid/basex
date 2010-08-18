package org.basex.query.func;

import static org.basex.query.QueryText.*;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.expr.Expr;
import org.basex.query.item.Bln;
import org.basex.query.item.Item;
import org.basex.query.item.Nod;
import org.basex.query.item.QNm;
import org.basex.query.item.SeqType;
import org.basex.query.item.Type;
import org.basex.query.iter.Iter;
import org.basex.query.iter.NodeIter;
import org.basex.query.iter.ItemIter;
import org.basex.query.util.Err;
import org.basex.util.InputInfo;
import org.basex.util.Token;

/**
 * Simple functions.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-10, ISC License
 * @author Christian Gruen
 */
public final class FNSimple extends Fun {
  /**
   * Constructor.
   * @param ii input info
   * @param f function definition
   * @param e arguments
   */
  protected FNSimple(final InputInfo ii, final FunDef f, final Expr... e) {
    super(ii, f, e);
  }

  @Override
  public Iter iter(final QueryContext ctx) throws QueryException {
    Iter ir = ctx.iter(expr[0]);
    switch(def) {
      case ONEORMORE:
        if(expr[0].type().mayBeZero()) ir = ItemIter.get(ir);
        if(ir.size() < 1) Err.or(input, EXP1M);
        return ir;
      case UNORDER:
        return ir;
      default:
        return super.iter(ctx);
    }
  }

  @Override
  public Item atomic(final QueryContext ctx, final InputInfo ii)
      throws QueryException {
    final Expr e = expr.length == 1 ? expr[0] : null;
    switch(def) {
      case FALSE:
        return Bln.FALSE;
      case TRUE:
        return Bln.TRUE;
      case EMPTY:
        return Bln.get(e.iter(ctx).next() == null);
      case EXISTS:
        return Bln.get(e.iter(ctx).next() != null);
      case BOOLEAN:
        return Bln.get(e.ebv(ctx, input).bool(input));
      case NOT:
        return Bln.get(!e.ebv(ctx, input).bool(input));
      case DEEPEQUAL:
        return Bln.get(deep(ctx));
      case ZEROORONE:
        Iter iter = e.iter(ctx);
        Item it = iter.next();
        if(it == null) return null;
        if(iter.next() != null) Err.or(input, EXP01);
        return it;
      case EXACTLYONE:
        iter = e.iter(ctx);
        it = iter.next();
        if(it == null || iter.next() != null) Err.or(input, EXP1);
        return it;
      default:
        return super.atomic(ctx, ii);
    }
  }

  @Override
  public Expr cmp(final QueryContext ctx) {
    // all functions have at least 1 argument
    final Expr e = expr[0];
    switch(def) {
      case BOOLEAN:
        expr[0] = expr[0].compEbv(ctx);
        return e.type().eq(SeqType.BLN) ? e : this;
      case NOT:
        if(e instanceof Fun) {
          final Fun fun = (Fun) e;
          if(fun.def == FunDef.EMPTY) {
            // simplify: not(empty(A)) -> exists(A)
            ctx.compInfo(OPTWRITE, this);
            expr = fun.expr;
            def = FunDef.EXISTS;
          } else if(fun.def == FunDef.EXISTS) {
            // simplify: not(exists(A)) -> empty(A)
            ctx.compInfo(OPTWRITE, this);
            expr = fun.expr;
            def = FunDef.EMPTY;
          } else {
            // simplify: not(boolean(A)) -> not(A)
            expr[0] = expr[0].compEbv(ctx);
          }
        }
        return this;
      case ZEROORONE:
        type = new SeqType(e.type().type, SeqType.Occ.ZO);
        return e.type().zeroOrOne() ? e : this;
      case EXACTLYONE:
        type = new SeqType(e.type().type, SeqType.Occ.O);
        return e.type().one() ? e : this;
      case ONEORMORE:
        type = new SeqType(e.type().type, SeqType.Occ.OM);
        return !e.type().mayBeZero() ? e : this;
      case UNORDER:
        return e;
      default:
        return this;
    }
  }

  @Override
  public Expr compEbv(final QueryContext ctx) {
    // all functions have at least 1 argument
    final Expr e = expr[0];
    Expr ex = this;
    if(def == FunDef.BOOLEAN) {
      // if(boolean(A)) -> if(A)
      ex = e;
    } else if(def == FunDef.EXISTS) {
      // if(exists(node*)) -> if(node*)
      if(e.type().type.node()) ex = e;
    }
    if(ex != this) ctx.compInfo(OPTWRITE, this);
    return ex;
  }

  /**
   * Checks items for deep equality.
   * @param ctx query context
   * @return result of check
   * @throws QueryException query exception
   */
  private boolean deep(final QueryContext ctx) throws QueryException {
    if(expr.length == 3) checkColl(expr[2], ctx);
    return deep(input, ctx.iter(expr[0]), ctx.iter(expr[1]));
  }

  /**
   * Checks items for deep equality.
   * @param ii input info
   * @param iter1 first iterator
   * @param iter2 second iterator
   * @return result of check
   * @throws QueryException query exception
   */
  public static boolean deep(final InputInfo ii, final Iter iter1,
      final Iter iter2) throws QueryException {

    // [CG] check if namespaces references are correctly compared
    Item it1 = null;
    Item it2 = null;
    // explicit non-short-circuit..
    while((it1 = iter1.next()) != null & (it2 = iter2.next()) != null) {
      if(!it1.equiv(ii, it2)) return false;
      if(!it1.node() && !it2.node()) continue;

      // comparing nodes
      if(!(it1.node() && it2.node())) return false;
      final NodeIter niter1 = ((Nod) it1).descOrSelf();
      final NodeIter niter2 = ((Nod) it2).descOrSelf();

      Nod n1 = null, n2 = null;
      while(true) {
        n1 = niter1.next();
        n2 = niter2.next();
        if(n1 == null && n2 == null || n1 == null ^ n2 == null) break;
        if(n1.type != n2.type) return false;

        final QNm qn1 = n1.qname();
        if(qn1 != null && !qn1.eq(n2.qname())) return false;

        if(n1.type == Type.ATT || n1.type == Type.PI || n1.type == Type.COM ||
            n1.type == Type.TXT) {
          if(!Token.eq(n1.atom(), n2.atom())) return false;
          continue;
        }

        NodeIter att1 = n1.attr();
        int s1 = 0;
        while(att1.next() != null) s1++;
        NodeIter att2 = n2.attr();
        int s2 = 0;
        while(att2.next() != null) s2++;
        if(s1 != s2) return false;

        Nod a1 = null, a2 = null;
        att1 = n1.attr();
        while((a1 = att1.next()) != null) {
          att2 = n2.attr();
          boolean found = false;
          while((a2 = att2.next()) != null) {
            if(a1.qname().eq(a2.qname())) {
              found = Token.eq(a1.atom(), a2.atom());
              break;
            }
          }
          if(!found) return false;
        }
      }
      if(n1 != n2) return false;
    }
    return it1 == it2;
  }
}
