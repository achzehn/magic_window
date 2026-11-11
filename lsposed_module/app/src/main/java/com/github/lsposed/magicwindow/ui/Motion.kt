package com.github.lsposed.magicwindow.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.github.lsposed.magicwindow.R

/**
 * 统一的动效参数与常用交互反馈。
 * 时长全部落在 150–300ms：按压 150ms，入场 220ms，列表错峰 40ms/项。
 */
object Motion {

    const val FAST = 150L
    const val NORMAL = 220L
    private const val STAGGER = 40L
    private const val MAX_STAGGER_ITEMS = 8
    private const val PRESS_SCALE = 0.97f

    /** 按压缩放反馈。返回 false，不拦截点击 / 长按事件。 */
    @SuppressLint("ClickableViewAccessibility")
    fun pressScale(vararg views: View) {
        views.forEach { view ->
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate()
                        .scaleX(PRESS_SCALE)
                        .scaleY(PRESS_SCALE)
                        .setDuration(FAST)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(FAST)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                false
            }
        }
    }

    /** 容器子视图错峰入场：淡入 + 上移 10dp。 */
    fun staggerIn(container: ViewGroup, startDelay: Long = 0) {
        val density = container.resources.displayMetrics.density
        val offset = 10f * density
        val count = minOf(container.childCount, MAX_STAGGER_ITEMS)
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = offset
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay + i * STAGGER)
                .setDuration(NORMAL)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** RecyclerView 首次填充的错峰入场动画（只在列表重新加载后播放一次）。 */
    fun playListEnterAnimation(recycler: RecyclerView) {
        recycler.layoutAnimation = android.view.animation.AnimationUtils
            .loadLayoutAnimation(recycler.context, R.anim.layout_app_list)
        recycler.scheduleLayoutAnimation()
    }
}