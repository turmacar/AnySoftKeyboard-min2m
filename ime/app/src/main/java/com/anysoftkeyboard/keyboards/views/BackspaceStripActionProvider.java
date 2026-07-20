package com.anysoftkeyboard.keyboards.views;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.anysoftkeyboard.api.KeyCodes;
import com.menny.android.anysoftkeyboard.R;

public class BackspaceStripActionProvider implements KeyboardViewContainerView.StripActionProvider {

  private static final int REPEAT_INITIAL_DELAY_MS = 400;
  private static final int REPEAT_INTERVAL_MS = 50;

  @NonNull private final OnKeyboardActionListener mKeyboardActionListener;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private boolean mRepeating;
  private ImageView mBackspaceIcon;
  private View mRootView;

  private final Runnable mRepeatRunnable =
      new Runnable() {
        @Override
        public void run() {
          mKeyboardActionListener.onKey(KeyCodes.DELETE, null, 0, null, true);
          mHandler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
      };

  public BackspaceStripActionProvider(@NonNull OnKeyboardActionListener listener) {
    mKeyboardActionListener = listener;
  }

  @Override
  public @NonNull View inflateActionView(@NonNull ViewGroup parent) {
    View root =
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.backspace_strip_action, parent, false);
    mRootView = root;
    mBackspaceIcon = root.findViewById(R.id.backspace_strip_action_icon);
    root.setOnClickListener(
        v -> mKeyboardActionListener.onKey(KeyCodes.DELETE, null, 0, null, true));
    root.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              mRepeating = false;
              mHandler.postDelayed(
                  () -> {
                    mRepeating = true;
                    mRepeatRunnable.run();
                  },
                  REPEAT_INITIAL_DELAY_MS);
              break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              mHandler.removeCallbacksAndMessages(null);
              if (mRepeating) {
                mKeyboardActionListener.onRelease(KeyCodes.DELETE);
                mRepeating = false;
                v.performClick();
                return true;
              }
              break;
          }
          return false;
        });
    return root;
  }

  public void setIcon(@Nullable Drawable icon) {
    if (mBackspaceIcon != null && icon != null) {
      mBackspaceIcon.setImageDrawable(icon);
    }
  }

  /**
   * Sets the background color for the strip action button to match the theme.
   */
  public void setThemeBackground(int normalColor, int pressedColor) {
    if (mRootView == null) return;
    StateListDrawable bg = new StateListDrawable();
    GradientDrawable pressed = new GradientDrawable();
    pressed.setShape(GradientDrawable.RECTANGLE);
    pressed.setColor(pressedColor);
    GradientDrawable normal = new GradientDrawable();
    normal.setShape(GradientDrawable.RECTANGLE);
    normal.setColor(normalColor);
    bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
    bg.addState(new int[]{}, normal);
    mRootView.setBackground(bg);
  }

  @Override
  public void onRemoved() {
    mHandler.removeCallbacksAndMessages(null);
    mRepeating = false;
  }
}
