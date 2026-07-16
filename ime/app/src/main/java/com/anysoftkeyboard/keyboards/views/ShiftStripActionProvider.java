package com.anysoftkeyboard.keyboards.views;

import android.graphics.drawable.Drawable;
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

  public ShiftStripActionProvider(@NonNull OnKeyboardActionListener listener) {
    mKeyboardActionListener = listener;
  }

  @Override
  public @NonNull View inflateActionView(@NonNull ViewGroup parent) {
    View root =
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.shift_strip_action, parent, false);
    mShiftIcon = root.findViewById(R.id.shift_strip_action_icon);
    root.setOnClickListener(
        v -> {
          mKeyboardActionListener.onPress(com.anysoftkeyboard.api.KeyCodes.SHIFT);
          mKeyboardActionListener.onRelease(com.anysoftkeyboard.api.KeyCodes.SHIFT);
        });
    return root;
  }

  public void setIcon(@Nullable Drawable icon) {
    if (mShiftIcon != null && icon != null) {
      mShiftIcon.setImageDrawable(icon);
    }
  }

  public void updateShiftState(boolean shifted, boolean locked) {
    if (mShiftIcon != null) {
      if (locked) {
        mShiftIcon.setImageLevel(2);
      } else if (shifted) {
        mShiftIcon.setImageLevel(1);
      } else {
        mShiftIcon.setImageLevel(0);
      }
    }
  }

  @Override
  public void onRemoved() {}
}
