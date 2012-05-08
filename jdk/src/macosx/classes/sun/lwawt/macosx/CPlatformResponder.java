/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.lwawt.macosx;

import sun.awt.SunToolkit;
import sun.lwawt.LWWindowPeer;
import sun.lwawt.macosx.event.NSEvent;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.KeyEvent;

/**
 * Translates NSEvents/NPCocoaEvents into AWT events.
 */
final class CPlatformResponder {

    private final LWWindowPeer peer;
    private final boolean isNpapiCallback;

    CPlatformResponder(final LWWindowPeer peer, final boolean isNpapiCallback) {
        this.peer = peer;
        this.isNpapiCallback = isNpapiCallback;
    }

    /**
     * Handles mouse events.
     */
    void handleMouseEvent(int eventType, int modifierFlags, int buttonNumber,
                          int clickCount, int x, int y, int absoluteX,
                          int absoluteY) {
        final SunToolkit tk = (SunToolkit)Toolkit.getDefaultToolkit();
        if ((buttonNumber > 2 && !tk.areExtraMouseButtonsEnabled())
                || buttonNumber > tk.getNumberOfButtons() - 1) {
            return;
        }

        int jeventType = isNpapiCallback ? NSEvent.npToJavaEventType(eventType) :
                                           NSEvent.nsToJavaEventType(eventType);

        int jbuttonNumber = MouseEvent.NOBUTTON;
        int jclickCount = 0;

        if (jeventType != MouseEvent.MOUSE_MOVED &&
            jeventType != MouseEvent.MOUSE_ENTERED &&
            jeventType != MouseEvent.MOUSE_EXITED)
        {
            jbuttonNumber = NSEvent.nsToJavaButton(buttonNumber);
            jclickCount = clickCount;
        }

        int jmodifiers = NSEvent.nsToJavaMouseModifiers(buttonNumber,
                                                        modifierFlags);
        boolean jpopupTrigger = NSEvent.isPopupTrigger(jmodifiers);

        peer.dispatchMouseEvent(jeventType, System.currentTimeMillis(), jbuttonNumber,
                                x, y, absoluteX, absoluteY, jmodifiers, jclickCount,
                                jpopupTrigger, null);
    }

    /**
     * Handles scroll events.
     */
    void handleScrollEvent(final int x, final int y, final int modifierFlags,
                           final double deltaX, final double deltaY) {
        final int buttonNumber = CocoaConstants.kCGMouseButtonCenter;
        int jmodifiers = NSEvent.nsToJavaMouseModifiers(buttonNumber,
                                                        modifierFlags);
        final boolean isShift = (jmodifiers & InputEvent.SHIFT_DOWN_MASK) != 0;

        // Vertical scroll.
        if (!isShift && deltaY != 0.0) {
            dispatchScrollEvent(x, y, jmodifiers, deltaY);
        }
        // Horizontal scroll or shirt+vertical scroll.
        final double delta = isShift && deltaY != 0.0 ? deltaY : deltaX;
        if (delta != 0.0) {
            jmodifiers |= InputEvent.SHIFT_DOWN_MASK;
            dispatchScrollEvent(x, y, jmodifiers, delta);
        }
    }

    private void dispatchScrollEvent(final int x, final int y,
                                     final int modifiers, final double delta) {
        final long when = System.currentTimeMillis();
        final int scrollType = MouseWheelEvent.WHEEL_UNIT_SCROLL;
        final int scrollAmount = 1;
        peer.dispatchMouseWheelEvent(when, x, y, modifiers, scrollType,
                                     scrollAmount, (int) -delta, -delta, null);
    }

    /**
     * Handles key events.
     */
    void handleKeyEvent(int eventType, int modifierFlags, String chars,
                        short keyCode, boolean needsKeyTyped) {
        boolean isFlagsChangedEvent =
            isNpapiCallback ? (eventType == CocoaConstants.NPCocoaEventFlagsChanged) :
                              (eventType == CocoaConstants.NSFlagsChanged);

        int jeventType = KeyEvent.KEY_PRESSED;
        int jkeyCode = KeyEvent.VK_UNDEFINED;
        int jkeyLocation = KeyEvent.KEY_LOCATION_UNKNOWN;
        boolean postsTyped = false;

        char testChar = KeyEvent.CHAR_UNDEFINED;
        char testDeadChar = 0;

        if (isFlagsChangedEvent) {
            int[] in = new int[] {modifierFlags, keyCode};
            int[] out = new int[3]; // [jkeyCode, jkeyLocation, jkeyType]

            NSEvent.nsKeyModifiersToJavaKeyInfo(in, out);

            jkeyCode = out[0];
            jkeyLocation = out[1];
            jeventType = out[2];
        } else {
            if (chars != null && chars.length() > 0) {
                testChar = chars.charAt(0);
            }

            int[] in = new int[] {testChar, testDeadChar, modifierFlags, keyCode};
            int[] out = new int[2]; // [jkeyCode, jkeyLocation]

            postsTyped = NSEvent.nsToJavaKeyInfo(in, out);
            if (!postsTyped) {
                testChar = KeyEvent.CHAR_UNDEFINED;
            }

            jkeyCode = out[0];
            jkeyLocation = out[1];
            jeventType = isNpapiCallback ? NSEvent.npToJavaEventType(eventType) :
                                           NSEvent.nsToJavaEventType(eventType);
        }

        char javaChar = NSEvent.nsToJavaChar(testChar, modifierFlags);
        // Some keys may generate a KEY_TYPED, but we can't determine
        // what that character is. That's likely a bug, but for now we
        // just check for CHAR_UNDEFINED.
        if (javaChar == KeyEvent.CHAR_UNDEFINED) {
            postsTyped = false;
        }


        int jmodifiers = NSEvent.nsToJavaKeyModifiers(modifierFlags);
        long when = System.currentTimeMillis();

        peer.dispatchKeyEvent(jeventType, when, jmodifiers,
                              jkeyCode, javaChar, jkeyLocation);

        // Current browser may be sending input events, so don't
        // post the KEY_TYPED here.
        postsTyped &= needsKeyTyped;

        // That's the reaction on the PRESSED (not RELEASED) event as it comes to
        // appear in MacOSX.
        // Modifier keys (shift, etc) don't want to send TYPED events.
        // On the other hand we don't want to generate keyTyped events
        // for clipboard related shortcuts like Meta + [CVX]
        boolean isMetaDown = (jmodifiers & KeyEvent.META_DOWN_MASK) != 0;
        if (jeventType == KeyEvent.KEY_PRESSED && postsTyped && !isMetaDown) {
            peer.dispatchKeyEvent(KeyEvent.KEY_TYPED, when, jmodifiers,
                                  KeyEvent.VK_UNDEFINED, javaChar,
                                  KeyEvent.KEY_LOCATION_UNKNOWN);
        }
    }

    void handleInputEvent(String text) {
        if (text != null) {
            int index = 0, length = text.length();
            char c;
            while (index < length) {
                c = text.charAt(index);
                peer.dispatchKeyEvent(KeyEvent.KEY_TYPED,
                                      System.currentTimeMillis(),
                                      0, KeyEvent.VK_UNDEFINED, c,
                                      KeyEvent.KEY_LOCATION_UNKNOWN);
                index++;
            }
        }
    }
}
