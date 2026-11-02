package com.fardin.floatshot

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/**
 * The round floating button. Drags are handled by a touch listener in
 * BubbleService; a plain tap ends in performClick() so TalkBack and
 * Switch Access can take a screenshot too.
 */
class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    override fun performClick(): Boolean = super.performClick()
}
