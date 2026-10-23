package com.synseria.synema;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A card's frame: a 16:9 picture inset by card_inset, so lifting the card never pushes the picture over
 * the focus ring drawn on the frame's edge. Its height follows the width it is given.
 */
final class Card extends FrameLayout {
    Card(Context ctx) {
        super(ctx);
        int p = ctx.getResources().getDimensionPixelSize(R.dimen.card_inset);
        setPadding(p, p, p, p);
    }

    /** A focusable card (Focus.card) showing {@code picture}, read out as {@code name}. */
    static Card of(Context ctx, Drawable picture, CharSequence name) {
        Card card = new Card(ctx);
        Focus.card(card);
        card.setContentDescription(name);
        ImageView img = new RoundedImageView(ctx);
        img.setImageDrawable(picture);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return card;
    }

    /** The picture's height for its width: 16:9. */
    static int pictureHeight(int pictureWidth) {
        return Math.max(0, pictureWidth) * 9 / 16;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int h = pictureHeight(w) + getPaddingTop() + getPaddingBottom();
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
