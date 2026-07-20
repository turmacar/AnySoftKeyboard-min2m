package com.anysoftkeyboard.keyboards.views;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.menny.android.anysoftkeyboard.R;

public class ShiftStripActionProvider implements KeyboardViewContainerView.StripActionProvider {

  @NonNull private final OnKeyboardActionListener mKeyboardActionListener;
  private ImageView mShiftIcon;
  private View mRootView;
  @Nullable private Drawable mShiftOff;
  @Nullable private Drawable mShiftOn;
  @Nullable private Drawable mShiftLocked;

  public ShiftStripActionProvider(@NonNull OnKeyboardActionListener listener) {
    mKeyboardActionListener = listener;
  }

  @Override
  public @NonNull View inflateActionView(@NonNull ViewGroup parent) {
    View root =
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.shift_strip_action, parent, false);
    mRootView = root;
    mShiftIcon = root.findViewById(R.id.shift_strip_action_icon);
    mShiftIcon.setImageResource(R.drawable.ic_shift_off);
    root.setOnClickListener(
        v -> {
          mKeyboardActionListener.onPress(com.anysoftkeyboard.api.KeyCodes.SHIFT);
          mKeyboardActionListener.onRelease(com.anysoftkeyboard.api.KeyCodes.SHIFT);
        });
    return root;
  }

  public void updateShiftState(boolean shifted, boolean locked) {
    if (mShiftIcon != null) {
      if (locked) {
        mShiftIcon.setImageDrawable(mShiftLocked != null ? mShiftLocked
            : mShiftIcon.getContext().getDrawable(R.drawable.ic_shift_locked));
      } else if (shifted) {
        mShiftIcon.setImageDrawable(mShiftOn != null ? mShiftOn
            : mShiftIcon.getContext().getDrawable(R.drawable.ic_shift_on));
      } else {
        mShiftIcon.setImageDrawable(mShiftOff != null ? mShiftOff
            : mShiftIcon.getContext().getDrawable(R.drawable.ic_shift_off));
      }
    }
  }

  /**
   * Sets themed shift icons from the keyboard view's icon set.
   */
  public void setThemedIcons(@Nullable Drawable shiftOff, @Nullable Drawable shiftOn,
      @Nullable Drawable shiftLocked) {
    mShiftOff = shiftOff;
    mShiftOn = shiftOn;
    mShiftLocked = shiftLocked;
    // Apply immediately
    updateShiftState(mShiftOn != null && mShiftIcon != null
        && mShiftIcon.getDrawable() == mShiftOn, false);
  }

  /**
   * Sets the background color for the strip action button to match the theme.
   * Creates a square colored rectangle matching the original strip action style.
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
  public void onRemoved() {}
}
