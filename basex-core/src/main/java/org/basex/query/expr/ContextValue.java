package org.basex.query.expr;

import org.basex.core.locks.*;
import org.basex.data.*;
import org.basex.query.*;
import org.basex.query.util.*;
import org.basex.query.value.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * Context value.
 *
 * @author BaseX Team 2005-20, BSD License
 * @author Christian Gruen
 */
public final class ContextValue extends Simple {
  /** Data reference (can be {@code null}). */
  private Data data;

  /**
   * Constructor.
   * @param info input info
   */
  public ContextValue(final InputInfo info) {
    super(info, SeqType.ITEM_ZM);
  }

  @Override
  public Expr optimize(final CompileContext cc) {
    final Value value = cc.qc.focus.value;
    if(value != null) {
      if(!cc.nestedFocus()) return cc.replaceWith(this, value);
      adoptType(value);
    }
    return this;
  }

  @Override
  public Value value(final QueryContext qc) throws QueryException {
    return ctxValue(qc);
  }

  @Override
  public boolean has(final Flag... flags) {
    return Flag.CTX.in(flags);
  }

  @Override
  public Expr inline(final ExprInfo ei, final Expr ex, final CompileContext cc) {
    // inline context or return null
    return ei == null ? ex : null;
  }

  @Override
  public Data data() {
    return data;
  }

  @Override
  public void data(final Data dt) {
    data = dt;
  }

  @Override
  public Expr copy(final CompileContext cc, final IntObjMap<Var> vm) {
    return copyType(new ContextValue(info));
  }

  @Override
  public boolean accept(final ASTVisitor visitor) {
    return visitor.lock(Locking.CONTEXT, false) && super.accept(visitor);
  }

  @Override
  public boolean equals(final Object obj) {
    return obj instanceof ContextValue;
  }

  @Override
  public void plan(final QueryString qs) {
    qs.token(".");
  }
}
