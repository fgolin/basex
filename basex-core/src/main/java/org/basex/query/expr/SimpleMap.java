package org.basex.query.expr;

import static org.basex.query.QueryText.*;

import java.util.*;
import java.util.function.*;

import org.basex.data.*;
import org.basex.query.*;
import org.basex.query.CompileContext.*;
import org.basex.query.expr.path.*;
import org.basex.query.func.Function;
import org.basex.query.util.*;
import org.basex.query.util.list.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.seq.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;

/**
 * Simple map operator.
 *
 * @author BaseX Team 2005-20, BSD License
 * @author Christian Gruen
 */
public abstract class SimpleMap extends Arr {
  /**
   * Constructor.
   * @param info input info
   * @param exprs expressions
   */
  SimpleMap(final InputInfo info, final Expr... exprs) {
    super(info, SeqType.ITEM_ZM, exprs);
  }

  /**
   * Creates a new, optimized map expression, or the first expression if only one was specified.
   * @param cc compilation context
   * @param ii input info
   * @param exprs one or more expressions
   * @return filter root, path or filter expression
   * @throws QueryException query exception
   */
  public static Expr get(final CompileContext cc, final InputInfo ii, final Expr... exprs)
      throws QueryException {
    return exprs.length == 1 ? exprs[0] : new CachedMap(ii, exprs).optimize(cc);
  }

  @Override
  public final void checkUp() throws QueryException {
    final int el = exprs.length;
    for(int e = 0; e < el - 1; e++) checkNoUp(exprs[e]);
    exprs[el - 1].checkUp();
  }

  @Override
  public final Expr compile(final CompileContext cc) throws QueryException {
    final int el = exprs.length;
    for(int e = 0; e < el; e++) {
      Expr expr = exprs[e];
      try {
        expr = expr.compile(cc);
      } catch(final QueryException qe) {
        // replace original expression with error
        expr = cc.error(qe, this);
      }
      if(e == 0) cc.pushFocus(expr);
      else cc.updateFocus(expr);
      exprs[e] = expr;
    }
    cc.removeFocus();
    return optimize(cc);
  }

  @Override
  public final Expr optimize(final CompileContext cc) throws QueryException {
    // flatten nested expressions (unless result needs to be cached)
    final ExprList list = new ExprList(exprs.length);
    for(final Expr expr : exprs) {
      if(expr instanceof SimpleMap && !(expr instanceof CachedMap)) {
        list.add(((SimpleMap) expr).exprs);
        cc.info(OPTFLAT_X_X, expr, (Supplier<?>) this::description);
      } else {
        list.add(expr);
      }
    }
    if(list.size() != exprs.length) return SimpleMap.get(cc, info, list.finish());
    exprs = list.next();

    // determine type and result size, drop expressions that will never be evaluated
    long min = 1, max = 1;
    boolean item = true;
    for(final Expr expr : exprs) {
      // no results: skip evaluation of remaining expressions
      if(max == 0) break;
      list.add(expr);
      final long es = expr.size();
      if(es == 0) {
        min = 0;
        max = 0;
      } else if(es > 0) {
        min *= es;
        if(max != -1) max *= es;
        if(es > 1) item = false;
      } else {
        final Occ o = expr.seqType().occ;
        if(o.min == 0) min = 0;
        if(o.max > 1) {
          max = -1;
          item = false;
        }
      }
    }
    if(exprs.length != list.size()) {
      exprs = list.finish();
      cc.info(OPTSIMPLE_X_X, (Supplier<?>) this::description, this);
    }
    exprType.assign(exprs[exprs.length - 1].seqType().type, new long[] { min, max });

    // no results, deterministic expressions: return empty sequence
    if(size() == 0 && !has(Flag.NDT)) return cc.emptySeq(this);

    // simplify static expressions
    int e = 0;
    final int el = exprs.length;
    boolean pushed = false;
    for(int n = 1; n < el; n++) {
      final Expr expr = exprs[e], next = exprs[n];
      if(e > 0) {
        if(pushed) {
          cc.updateFocus(expr);
        } else {
          cc.pushFocus(expr);
          pushed = true;
        }
      }

      final long es = expr.size();
      Expr ex = null;
      if(next instanceof Filter) {
        final Filter filter = (Filter) next;
        if(filter.root instanceof ContextValue && !filter.mayBePositional()) {
          // merge filter with context value as root
          // A ! .[B]  ->  A[B]
          ex = Filter.get(cc, info, expr, ((Filter) next).exprs);
        }
      }

      if(ex == null && es != -1 && !expr.has(Flag.NDT)) {
        // check if deterministic expressions with known result size can be removed
        // expression size is never 0 (empty expressions have no followers, see above)
        if(next instanceof Value) {
          // rewrite expression with next value as singleton sequence
          // (1 to 2) ! 3  ->  (3, 3)
          ex = SingletonSeq.get((Value) next, es);
        } else if(!next.has(Flag.POS)) {
          // check if next expression relies on the context
          if(next.has(Flag.CTX)) {
            if(expr instanceof ContextValue) {
              // replace leading context reference
              // . ! number() = 2  ->  number() = 2
              ex = next;
            } else if(es == 1 && (expr instanceof Value || expr instanceof VarRef)) {
              // single item: inline values and variable references
              // 'a' ! (. = 'a')  ->  'a  = 'a'
              // map {} ! ?*      ->  map {}?*
              // 123 ! number()   ->  number(123)
              // $doc ! /         ->  $doc
              try {
                ex = next.inline(null, expr, cc);
                // ignore rewritten expression that is identical to original one
                if(ex instanceof SimpleMap) {
                  final Expr[] tmp = ((SimpleMap) ex).exprs;
                  if(tmp[0] == expr && tmp[1] == next) ex = null;
                }
              } catch(final QueryException qe) {
                // replace original expression with error
                ex = cc.error(qe, this);
              }
            }
          } else if(es == 1) {
            // replace expression with next expression
            // <x/> ! 'ok'  ->  'ok'
            ex = next;
          } else if(!next.has(Flag.NDT, Flag.CNS)) {
            // replace expression with replicated expression
            // (1 to 2) ! 'ok'  ->  util:replicate('ok', 2)
            ex = cc.function(Function._UTIL_REPLICATE, info, next, Int.get(es));
          } else {
            // (1 to 2) ! <x/>  ->  util:replicate('', 2) ! <x/>
            exprs[e] = cc.replaceWith(exprs[e], SingletonSeq.get(Str.ZERO, es));
          }
        }
      }

      if(ex != null) {
        cc.info(OPTMERGE_X, ex);
        exprs[e] = ex;
      } else if(!(next instanceof ContextValue)) {
        // context item expression can be ignored
        exprs[++e] = next;
      }
    }
    if(pushed) cc.removeFocus();

    // single expression: return this expression
    if(e == 0) return exprs[0];
    if(++e != el) exprs = Arrays.copyOf(exprs, e);

    // choose best map implementation
    return copyType(
      item ? new ItemMap(info, exprs) :
      iterative(exprs) ? new IterMap(info, exprs) :
      new CachedMap(info, exprs));
  }

  /**
   * Checks if the specified expressions can be evaluated iteratively.
   * @param exprs expressions
   * @return result of check
   */
  private static boolean iterative(final Expr... exprs) {
    for(final Expr expr : exprs) {
      if(expr.has(Flag.POS)) return false;
    }
    return true;
  }

  @Override
  public Data data() {
    return exprs[exprs.length - 1].data();
  }

  /**
   * Converts the map to a path expression.
   * @param cc compilation context
   * @return converted or original expression
   * @throws QueryException query context
   */
  public Expr toPath(final CompileContext cc) throws QueryException {
    Expr root = exprs[0];
    final ExprList steps = new ExprList();
    if(root instanceof AxisPath) {
      final AxisPath path = (AxisPath) root;
      root = path.root;
      steps.add(path.steps);
    }
    final int el = exprs.length;
    for(int e = 1; e < el; e++) {
      if(!(exprs[e] instanceof AxisPath)) return this;
      final AxisPath path = (AxisPath) exprs[e];
      if(path.root != null) return this;
      steps.add(path.steps);
    }
    return cc.replaceWith(this, Path.get(cc, info, root, steps.finish()));
  }

  @Override
  public final Expr simplifyFor(final Simplify mode, final CompileContext cc)
      throws QueryException {

    Expr expr = this;
    if(mode == Simplify.EBV || mode == Simplify.PREDICATE || mode == Simplify.DISTINCT) {
      // nodes ! text() = string  ->  nodes/text() = string
      expr = toPath(cc);
    } else {
      final int el = exprs.length;
      final Expr old = exprs[el - 1];
      final Expr ex = cc.get(exprs[el - 2], () -> old.simplifyFor(mode, cc));
      if(ex != old) {
        final ExprList list = new ExprList(el).add(exprs).set(el - 1, ex);
        expr = SimpleMap.get(cc, info, list.finish());
      }
    }
    return expr != this ? expr : super.simplifyFor(mode, cc);
  }

  @Override
  public final boolean has(final Flag... flags) {
    /* Context dependency: Only check first expression.
     * Examples: . ! abc */
    if(Flag.CTX.in(flags) && exprs[0].has(Flag.CTX)) return true;
    /* Positional access: only check root node (steps will refer to result of root node).
     * Example: position()/a */
    if(Flag.POS.in(flags) && exprs[0].has(Flag.POS)) return true;
    // check remaining flags
    final Flag[] flgs = Flag.POS.remove(Flag.CTX.remove(flags));
    return flgs.length != 0 && super.has(flgs);
  }

  @Override
  public final boolean accept(final ASTVisitor visitor) {
    visitor.enterFocus();
    if(!visitAll(visitor, exprs)) return false;
    visitor.exitFocus();
    return true;
  }

  @Override
  public final VarUsage count(final Var var) {
    VarUsage uses = VarUsage.NEVER;
    final int el = exprs.length;
    for(int e = 1; e < el; e++) {
      uses = uses.plus(exprs[e].count(var));
      if(uses == VarUsage.MORE_THAN_ONCE) break;
    }
    return uses == VarUsage.NEVER ? exprs[0].count(var) : VarUsage.MORE_THAN_ONCE;
  }

  @Override
  public final boolean inlineable(final Var var) {
    final int el = exprs.length;
    for(int e = 1; e < el; e++) {
      if(exprs[e].uses(var)) return false;
    }
    return exprs[0].inlineable(var);
  }

  @Override
  public final Expr inline(final ExprInfo ei, final Expr ex, final CompileContext cc)
      throws QueryException {

    boolean changed = false;
    // context inlining: only consider first expression
    final int el = ei == null ? 1 : exprs.length;
    for(int e = 0; e < el; e++) {
      Expr inlined;
      try {
        inlined = exprs[e].inline(ei, ex, cc);
      } catch(final QueryException qe) {
        // replace original expression with error
        inlined = cc.error(qe, this);
      }
      if(inlined != null) {
        exprs[e] = inlined;
        changed = true;
      } else {
        inlined = exprs[e];
      }
      if(e == 0) cc.pushFocus(inlined);
      else cc.updateFocus(inlined);
    }
    cc.removeFocus();

    return changed ? optimize(cc) : null;
  }

  @Override
  public boolean equals(final Object obj) {
    return this == obj || obj instanceof SimpleMap && super.equals(obj);
  }

  @Override
  public String description() {
    return "map operator";
  }

  @Override
  public void plan(final QueryString qs) {
    qs.tokens(exprs, " ! ");
  }
}
