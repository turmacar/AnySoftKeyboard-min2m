package com.anysoftkeyboard.keyboards.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import com.anysoftkeyboard.ime.InputViewActionsProvider;
import com.anysoftkeyboard.ime.InputViewBinder;
import com.anysoftkeyboard.keyboards.views.extradraw.ExtraDraw;
import com.anysoftkeyboard.overlay.OverlayData;
import com.anysoftkeyboard.overlay.OverlayDataImpl;
import com.anysoftkeyboard.theme.KeyboardTheme;
import com.menny.android.anysoftkeyboard.BuildConfig;
import com.menny.android.anysoftkeyboard.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.evendanan.pixel.MainChild;

public class KeyboardViewContainerView extends ViewGroup implements ThemeableChild {

  private static final int PROVIDER_TAG_ID = R.id.keyboard_container_provider_tag_id;
  private static final int LEFT_PROVIDER_TAG_ID = R.id.keyboard_container_left_provider_tag_id;
  private static final int RIGHT_PROVIDER_TAG_ID = R.id.keyboard_container_right_provider_tag_id;
  private static final int TOP_PROVIDER_TAG_ID = R.id.keyboard_container_top_provider_tag_id;
  private static final int FIRST_PROVIDER_VIEW_INDEX = 2;

  private static final boolean SHOW_CLICKS = BuildConfig.DEBUG && false;

  private final int mActionStripHeight;
  private final List<View> mStripActionViews = new ArrayList<>();
  private final List<View> mLeftStripActionViews = new ArrayList<>();
  private final List<View> mRightStripActionViews = new ArrayList<>();
  private final List<View> mTopStripActionViews = new ArrayList<>();
  private boolean mShowActionStrip = true;
  private boolean mCompactMode = false;
  private InputViewBinder mStandardKeyboardView;
  private CandidateView mCandidateView;
  private OnKeyboardActionListener mKeyboardActionListener;
  private KeyboardTheme mKeyboardTheme;
  private OverlayData mOverlayData = new OverlayDataImpl();
  private final Rect mExtraPaddingToMainKeyboard = new Rect();
  private ClicksExtraDraw mClicksDrawer;

  private int mBottomPadding;

  public KeyboardViewContainerView(Context context) {
    super(context);
    mActionStripHeight = getResources().getDimensionPixelSize(R.dimen.candidate_strip_height);
    constructorInit();
  }

  public KeyboardViewContainerView(Context context, AttributeSet attrs) {
    super(context, attrs);
    mActionStripHeight = getResources().getDimensionPixelSize(R.dimen.candidate_strip_height);
    constructorInit();
  }

  public KeyboardViewContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    mActionStripHeight = getResources().getDimensionPixelSize(R.dimen.candidate_strip_height);
    constructorInit();
  }

  public KeyboardViewContainerView(
      Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    mActionStripHeight = getResources().getDimensionPixelSize(R.dimen.candidate_strip_height);
    constructorInit();
  }

  private void constructorInit() {
    if (SHOW_CLICKS) {
      mClicksDrawer = new ClicksExtraDraw();
    }
    setWillNotDraw(false);
  }

  @Override
  public void onViewAdded(View child) {
    super.onViewAdded(child);
    if (mKeyboardActionListener != null && child instanceof InputViewActionsProvider c) {
      c.setOnKeyboardActionListener(mKeyboardActionListener);
    }

    setThemeForChildView(child);
    setActionsStripVisibility(mShowActionStrip);

    if (child instanceof MainChild c) {
      c.setBottomOffset(mBottomPadding);
    }

    switch (child.getId()) {
      case R.id.candidate_view -> mCandidateView = (CandidateView) child;
      case R.id.AnyKeyboardMainView -> mStandardKeyboardView = (InputViewBinder) child;
    }
  }

  @Override
  public void onViewRemoved(View child) {
    super.onViewRemoved(child);
    setActionsStripVisibility(mShowActionStrip);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (SHOW_CLICKS) {
      mClicksDrawer.addClick(ev);
    }
    if (mExtraPaddingToMainKeyboard.contains((int) ev.getX(), (int) ev.getY())) {
      // offsetting
      ev.setLocation(ev.getX(), mExtraPaddingToMainKeyboard.bottom + 1f);
    }
    if (SHOW_CLICKS) {
      mClicksDrawer.addTranslatedClick(ev);
      if (mClicksDrawer.shouldDraw()) {
        if (mStandardKeyboardView instanceof AnyKeyboardViewWithExtraDraw extraDrawer) {
          extraDrawer.addExtraDraw(mClicksDrawer);
        }
      }
    }
    return false;
  }

  public void setActionsStripVisibility(boolean requestedVisibility) {
    mShowActionStrip = requestedVisibility;
    if (mCandidateView != null) {
      // calculating the actual needed visibility:
      // at least one visible view which is a ActionsStripSupportedChild
      var visible = false;
      for (int childIndex = 0; childIndex < getChildCount(); childIndex++) {
        var child = getChildAt(childIndex);
        if (child.getVisibility() == View.VISIBLE) {
          if (child instanceof ActionsStripSupportedChild) {
            visible = requestedVisibility;
            break;
          }
        }
      }

      final int targetVisibility = visible ? View.VISIBLE : View.GONE;
      if (targetVisibility != mCandidateView.getVisibility()) {
        mCandidateView.setVisibility(targetVisibility);

        for (View stripActionView : mStripActionViews) {
          if (visible) {
            if (stripActionView.getParent() == null) {
              addView(stripActionView);
            }
          } else {
            removeView(stripActionView);
          }
        }

        for (View stripActionView : mLeftStripActionViews) {
          if (visible) {
            if (stripActionView.getParent() == null) {
              addView(stripActionView);
            }
          } else {
            removeView(stripActionView);
          }
        }

        for (View stripActionView : mRightStripActionViews) {
          if (visible) {
            if (stripActionView.getParent() == null) {
              addView(stripActionView);
            }
          } else {
            removeView(stripActionView);
          }
        }

        for (View stripActionView : mTopStripActionViews) {
          if (visible) {
            if (stripActionView.getParent() == null) {
              addView(stripActionView);
            }
          } else {
            removeView(stripActionView);
          }
        }

        invalidate();
      }
    }
  }

  public void setCompactMode(boolean compact) {
    mCompactMode = compact;
  }

  public void addStripAction(@NonNull StripActionProvider provider, boolean highPriority) {
    // In compact mode, redirect high-priority strip actions to the top row
    if (mCompactMode && highPriority) {
      addTopStripAction(provider);
      return;
    }
    for (var stripActionView : mStripActionViews) {
      if (stripActionView.getTag(PROVIDER_TAG_ID) == provider) {
        return;
      }
    }

    var actionView = provider.inflateActionView(this);
    if (actionView.getParent() != null)
      throw new IllegalStateException("StripActionProvider inflated a view with a parent!");
    actionView.setTag(PROVIDER_TAG_ID, provider);
    if (mShowActionStrip) {
      if (highPriority) {
        addView(actionView, FIRST_PROVIDER_VIEW_INDEX);
      } else {
        addView(actionView);
      }
    }

    if (highPriority) {
      mStripActionViews.add(0, actionView);
    } else {
      mStripActionViews.add(actionView);
    }

    invalidate();
  }

  public void addLeftStripAction(@NonNull StripActionProvider provider) {
    for (var stripActionView : mLeftStripActionViews) {
      if (stripActionView.getTag(LEFT_PROVIDER_TAG_ID) == provider) {
        return;
      }
    }

    var actionView = provider.inflateActionView(this);
    if (actionView.getParent() != null)
      throw new IllegalStateException("StripActionProvider inflated a view with a parent!");
    actionView.setTag(LEFT_PROVIDER_TAG_ID, provider);
    if (mShowActionStrip) {
      addView(actionView);
    }

    mLeftStripActionViews.add(actionView);
    invalidate();
  }

  public void addTopStripAction(@NonNull StripActionProvider provider) {
    for (var stripActionView : mTopStripActionViews) {
      if (stripActionView.getTag(TOP_PROVIDER_TAG_ID) == provider) {
        return;
      }
    }

    var actionView = provider.inflateActionView(this);
    if (actionView.getParent() != null)
      throw new IllegalStateException("StripActionProvider inflated a view with a parent!");
    actionView.setTag(TOP_PROVIDER_TAG_ID, provider);
    if (mShowActionStrip) {
      addView(actionView, FIRST_PROVIDER_VIEW_INDEX);
    }

    mTopStripActionViews.add(actionView);
    invalidate();
  }

  public void addRightStripAction(@NonNull StripActionProvider provider) {
    for (var stripActionView : mRightStripActionViews) {
      if (stripActionView.getTag(RIGHT_PROVIDER_TAG_ID) == provider) {
        return;
      }
    }

    var actionView = provider.inflateActionView(this);
    if (actionView.getParent() != null)
      throw new IllegalStateException("StripActionProvider inflated a view with a parent!");
    actionView.setTag(RIGHT_PROVIDER_TAG_ID, provider);
    if (mShowActionStrip) {
      addView(actionView);
    }

    mRightStripActionViews.add(actionView);
    invalidate();
  }

  public void removeStripAction(@NonNull StripActionProvider provider) {
    for (var stripActionView : mStripActionViews) {
      if (stripActionView.getTag(PROVIDER_TAG_ID) == provider) {
        removeView(stripActionView);
        provider.onRemoved();
        mStripActionViews.remove(stripActionView);
        invalidate();
        return;
      }
    }
    for (var stripActionView : mLeftStripActionViews) {
      if (stripActionView.getTag(LEFT_PROVIDER_TAG_ID) == provider) {
        removeView(stripActionView);
        provider.onRemoved();
        mLeftStripActionViews.remove(stripActionView);
        invalidate();
        return;
      }
    }
    for (var stripActionView : mRightStripActionViews) {
      if (stripActionView.getTag(RIGHT_PROVIDER_TAG_ID) == provider) {
        removeView(stripActionView);
        provider.onRemoved();
        mRightStripActionViews.remove(stripActionView);
        invalidate();
        return;
      }
    }
    for (var stripActionView : mTopStripActionViews) {
      if (stripActionView.getTag(TOP_PROVIDER_TAG_ID) == provider) {
        removeView(stripActionView);
        provider.onRemoved();
        mTopStripActionViews.remove(stripActionView);
        invalidate();
        return;
      }
    }
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    final int count = getChildCount();
    final int left = l + getPaddingLeft();
    final int right = r - getPaddingRight();
    int currentTop = t + getPaddingTop();

    // First pass: layout top strip actions in their own row above the candidate bar
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (child.getTag(TOP_PROVIDER_TAG_ID) != null) {
        child.layout(left, currentTop, right, currentTop + child.getMeasuredHeight());
        currentTop += child.getMeasuredHeight();
      }
    }

    // Second pass: calculate left-pinned width for candidate sandwiching
    final int actionsTop = currentTop;
    int actionRight = r - getPaddingRight();
    int actionLeft = l + getPaddingLeft();
    int leftPinnedWidth = 0;
    int rightPinnedWidth = 0;
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (child.getTag(LEFT_PROVIDER_TAG_ID) != null) {
        leftPinnedWidth += child.getMeasuredWidth();
      } else if (child.getTag(RIGHT_PROVIDER_TAG_ID) != null) {
        rightPinnedWidth += child.getMeasuredWidth();
      }
    }

    // Layout right-edge pinned actions first (far right, immovable)
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (child.getTag(RIGHT_PROVIDER_TAG_ID) != null) {
        child.layout(
            actionRight - child.getMeasuredWidth(),
            actionsTop,
            actionRight,
            actionsTop + child.getMeasuredHeight());
        actionRight -= child.getMeasuredWidth();
      }
    }

    // Third pass: layout candidate, left/right strip actions, and keyboard
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (child.getTag(TOP_PROVIDER_TAG_ID) != null) {
        // already laid out above
      } else if (child.getTag(RIGHT_PROVIDER_TAG_ID) != null) {
        // already laid out above
      } else if (child.getTag(LEFT_PROVIDER_TAG_ID) != null) {
        child.layout(
            actionLeft,
            actionsTop,
            actionLeft + child.getMeasuredWidth(),
            actionsTop + child.getMeasuredHeight());
        actionLeft += child.getMeasuredWidth();
      } else if (child.getTag(PROVIDER_TAG_ID) != null) {
        child.layout(
            actionRight - child.getMeasuredWidth(),
            actionsTop,
            actionRight,
            actionsTop + child.getMeasuredHeight());
        actionRight -= child.getMeasuredWidth();
      } else if (child == mCandidateView) {
        child.layout(left + leftPinnedWidth, currentTop, right - rightPinnedWidth, currentTop + child.getMeasuredHeight());
        currentTop += child.getMeasuredHeight();
      } else {
        child.layout(left, currentTop, right, currentTop + child.getMeasuredHeight());
        currentTop += child.getMeasuredHeight();
      }
    }
    // setting up the extra-offset for the main-keyboard
    final var mainKeyboard = ((View) mStandardKeyboardView);
    mainKeyboard.getHitRect(mExtraPaddingToMainKeyboard);
    mExtraPaddingToMainKeyboard.bottom = mainKeyboard.getTop();
    mExtraPaddingToMainKeyboard.top = mainKeyboard.getTop() - mActionStripHeight / 4;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int totalWidth = 0;
    int totalHeight = mCandidateView.getVisibility() == View.VISIBLE ? mActionStripHeight : 0;
    final int count = getChildCount();

    // First pass: measure strip actions and calculate consumed width
    int leftPinnedWidth = 0;
    int rightPinnedWidth = 0;
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (child.getTag(LEFT_PROVIDER_TAG_ID) != null) {
        measureChild(child, widthMeasureSpec, heightMeasureSpec);
        leftPinnedWidth += child.getMeasuredWidth();
      } else if (child.getTag(PROVIDER_TAG_ID) != null) {
        measureChild(child, widthMeasureSpec, heightMeasureSpec);
        rightPinnedWidth += child.getMeasuredWidth();
      } else if (child.getTag(RIGHT_PROVIDER_TAG_ID) != null) {
        measureChild(child, widthMeasureSpec, heightMeasureSpec);
        rightPinnedWidth += child.getMeasuredWidth();
      } else if (child.getTag(TOP_PROVIDER_TAG_ID) != null) {
        measureChild(child, widthMeasureSpec, heightMeasureSpec);
        totalHeight += child.getMeasuredHeight();
      }
    }

    // Second pass: measure CandidateView with reduced width, and other children
    int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
    int candidateWidth = parentWidth - leftPinnedWidth - rightPinnedWidth;
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (child.getTag(PROVIDER_TAG_ID) != null || child.getTag(LEFT_PROVIDER_TAG_ID) != null
          || child.getTag(RIGHT_PROVIDER_TAG_ID) != null
          || child.getTag(TOP_PROVIDER_TAG_ID) != null) {
        // already measured above
      } else if (child == mCandidateView) {
        int candidateMeasureSpec = MeasureSpec.makeMeasureSpec(candidateWidth, MeasureSpec.EXACTLY);
        measureChild(child, candidateMeasureSpec, heightMeasureSpec);
      } else {
        measureChild(child, widthMeasureSpec, heightMeasureSpec);
        totalWidth = Math.max(totalWidth, child.getMeasuredWidth());
        totalHeight += child.getMeasuredHeight();
      }
    }

    setMeasuredDimension(totalWidth, totalHeight);
  }

  private void setThemeForChildView(View child) {
    if (child instanceof ThemeableChild c && mKeyboardTheme != null) {
      c.setKeyboardTheme(mKeyboardTheme);
      c.setThemeOverlay(mOverlayData);
    }
  }

  public void setOnKeyboardActionListener(OnKeyboardActionListener keyboardActionListener) {
    mKeyboardActionListener = keyboardActionListener;
    for (int childIndex = 0; childIndex < getChildCount(); childIndex++) {
      View child = getChildAt(childIndex);
      if (child instanceof InputViewActionsProvider) {
        ((InputViewActionsProvider) child).setOnKeyboardActionListener(keyboardActionListener);
      }
    }
  }

  public CandidateView getCandidateView() {
    return mCandidateView;
  }

  public InputViewBinder getStandardKeyboardView() {
    return mStandardKeyboardView;
  }

  @Override
  public void setKeyboardTheme(KeyboardTheme theme) {
    mKeyboardTheme = theme;
    for (int childIndex = 0; childIndex < getChildCount(); childIndex++) {
      setThemeForChildView(getChildAt(childIndex));
    }
  }

  @Override
  public void setThemeOverlay(OverlayData overlay) {
    mOverlayData = overlay;
    for (int childIndex = 0; childIndex < getChildCount(); childIndex++) {
      setThemeForChildView(getChildAt(childIndex));
    }
  }

  public void setBottomPadding(int bottomPadding) {
    mBottomPadding = bottomPadding;
    for (int childIndex = 0; childIndex < getChildCount(); childIndex++) {
      if (getChildAt(childIndex) instanceof MainChild v) {
        v.setBottomOffset(bottomPadding);
      }
    }
  }

  public interface StripActionProvider {
    @NonNull
    View inflateActionView(@NonNull ViewGroup parent);

    void onRemoved();
  }

  private static class ClicksExtraDraw implements ExtraDraw {

    private final Random mRandom = new Random();
    private final Paint mPaint = new Paint();

    private final List<PointF> mClicks = new ArrayList<>();
    private final List<PointF> mTranslatedClicks = new ArrayList<>();

    private int mFrames;

    void addClick(MotionEvent ev) {
      mFrames = 0;
      mClicks.add(new PointF(ev.getX(), ev.getY()));
    }

    void addTranslatedClick(MotionEvent ev) {
      var click = mClicks.get(mClicks.size() - 1);
      var translated = new PointF(ev.getX() - click.x, ev.getY() - click.y);
      mTranslatedClicks.add(translated);
    }

    void clearClicks() {
      mClicks.clear();
      mTranslatedClicks.clear();
    }

    @Override
    public boolean onDraw(
        Canvas canvas, Paint keyValuesPaint, AnyKeyboardViewWithExtraDraw parentKeyboardView) {
      if (++mFrames > 200) {
        mFrames = 0;
        clearClicks();
      }

      canvas.translate(0, -parentKeyboardView.getPaddingBottom());
      for (int i = 0; i < mClicks.size(); i++) {
        var click = mClicks.get(i);
        var translated = mTranslatedClicks.get(i);

        canvas.translate(click.x, click.y);
        mPaint.setColor(
            Color.argb(
                255,
                150 + mRandom.nextInt(100),
                150 + mRandom.nextInt(100),
                150 + mRandom.nextInt(100)));
        mPaint.setStrokeWidth(2f);
        canvas.drawCircle(0, 0, 3, mPaint);
        canvas.drawCircle(translated.x, translated.y, 6, mPaint);
        canvas.drawLine(0, 0, translated.x, translated.y, mPaint);
        canvas.translate(-click.x, -click.y);
      }

      canvas.translate(0, parentKeyboardView.getPaddingBottom());

      return mFrames > 0;
    }

    public boolean shouldDraw() {
      return !mClicks.isEmpty() && mClicks.size() % 10 == 0;
    }
  }
}
