package org.basex.gui.layout;

import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JTextField;

/**
 * Project specific text field implementation.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 */
public class BaseXTextField extends JTextField {
  /** Default width of text fields. */
  public static final int TWIDTH = 240;
  /** Last Input. */
  String last = "";
  /** Button help. */
  byte[] help;

  /**
   * Constructor.
   * @param win parent window
   */
  public BaseXTextField(final Window win) {
    this(null, win);
  }

  /**
   * Constructor.
   * @param txt input text
   * @param win parent window
   */
  public BaseXTextField(final String txt, final Window win) {
    BaseXLayout.setWidth(this, TWIDTH);
    BaseXLayout.addInteraction(this, null, win);

    if(txt != null) {
      setText(txt);
      selectAll();
    }
    addMouseListener(new MouseAdapter() {
      @Override
      public void mouseEntered(final MouseEvent e) {
        BaseXLayout.focus(e.getComponent(), help);
      }
    });
    addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(final KeyEvent e) {
        final int c = e.getKeyCode();
        if(BaseXLayout.mod(e) && (c == KeyEvent.VK_Z || c == KeyEvent.VK_Y)) {
          final String t = getText();
          setText(last);
          last = t;
        }
      }
    });
  }

  /**
   * Adds search functionality to the text field.
   * @param area text area to search
   */
  final void addSearch(final BaseXText area) {
    final Font f = getFont();
    setFont(f.deriveFont((float) f.getSize() + 2));
    BaseXLayout.setWidth(this, 80);

    addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(final KeyEvent e) {
        final String text = getText();
        final int co = e.getKeyCode();
        final boolean enter = co == KeyEvent.VK_ENTER;
        if(co == KeyEvent.VK_ESCAPE || enter && text.isEmpty()) {
          area.requestFocusInWindow();
        } else if(enter || co == KeyEvent.VK_F3) {
          area.find(text, e.isShiftDown());
        }
      }
      @Override
      public void keyReleased(final KeyEvent e) {
        final String text = getText();
        final char ch = e.getKeyChar();
        if(ch != KeyEvent.VK_ENTER && Character.isDefined(ch))
          area.find(text, false);
        repaint();
      }
    });
    repaint();
  }

  @Override
  public void setText(final String txt) {
    last = txt;
    super.setText(txt);
  }

  @Override
  public final void setEnabled(final boolean sel) {
    super.setEnabled(sel);
    setOpaque(sel);
  }

  /**
   * Sets the text field help text.
   * @param hlp help text
   */
  public final void help(final byte[] hlp) {
    help = hlp;
  }
}
