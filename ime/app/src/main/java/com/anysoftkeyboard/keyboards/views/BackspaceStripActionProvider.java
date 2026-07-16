package com.anysoftkeyboard.keyboards.views;

import android.graphics.drawable.Drawable;
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

  @Override
  public void onRemoved() {
    mHandler.removeCallbacksAndMessages(null);
    mRepeating = false;
  }
}
