/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.pager

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.AutoTestFrameClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalFoundationApi::class)
@LargeTest
@RunWith(Parameterized::class)
class PagerStateTest(val config: ParamConfig) : BasePagerTest(config) {

    @Test
    fun pagerStateNotAttached_shouldReturnDefaultValues_andChangeAfterAttached() = runBlocking {
        // Arrange
        val state = PagerStateImpl(5, 0.2f) { DefaultPageCount }

        assertThat(state.currentPage).isEqualTo(5)
        assertThat(state.currentPageOffsetFraction).isEqualTo(0.2f)

        val currentPage = derivedStateOf { state.currentPage }
        val currentPageOffsetFraction = derivedStateOf { state.currentPageOffsetFraction }

        rule.setContent {
            HorizontalOrVerticalPager(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PagerTestTag)
                    .onSizeChanged { pagerSize = if (vertical) it.height else it.width },
                pageSize = PageSize.Fill,
                reverseLayout = config.reverseLayout,
                pageSpacing = config.pageSpacing,
                contentPadding = config.mainAxisContentPadding,
            ) {
                Page(index = it)
            }
        }

        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            state.scrollToPage(state.currentPage + 1)
        }

        rule.runOnIdle {
            assertThat(currentPage.value).isEqualTo(6)
            assertThat(currentPageOffsetFraction.value).isEqualTo(0.0f)
        }
    }

    @Test
    fun scrollToPage_shouldPlacePagesCorrectly() = runBlocking {
        // Arrange
        createPager(modifier = Modifier.fillMaxSize())

        // Act and Assert
        repeat(DefaultAnimationRepetition) {
            assertThat(pagerState.currentPage).isEqualTo(it)
            withContext(Dispatchers.Main + AutoTestFrameClock()) {
                pagerState.scrollToPage(pagerState.currentPage + 1)
            }
            confirmPageIsInCorrectPosition(pagerState.currentPage)
        }
    }

    @SdkSuppress(maxSdkVersion = 32) // b/269176638
    @Test
    fun scrollToPage_usedOffset_shouldPlacePagesCorrectly() = runBlocking {
        // Arrange

        suspend fun scrollToPageWithOffset(page: Int, offset: Float) {
            withContext(Dispatchers.Main + AutoTestFrameClock()) {
                pagerState.scrollToPage(page, offset)
            }
        }

        // Arrange
        createPager(modifier = Modifier.fillMaxSize())

        // Act
        scrollToPageWithOffset(10, 0.5f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 10, pageOffset = 0.5f)

        // Act
        scrollToPageWithOffset(4, 0.2f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 4, pageOffset = 0.2f)

        // Act
        scrollToPageWithOffset(12, -0.4f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 12, pageOffset = -0.4f)

        // Act
        scrollToPageWithOffset(DefaultPageCount - 1, 0.5f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, DefaultPageCount - 1)

        // Act
        scrollToPageWithOffset(0, -0.5f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 0)
    }

    @Test
    fun scrollToPage_longSkipShouldNotPlaceIntermediatePages() = runBlocking {
        // Arrange

        createPager(modifier = Modifier.fillMaxSize())

        // Act
        assertThat(pagerState.currentPage).isEqualTo(0)
        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            pagerState.scrollToPage(DefaultPageCount - 1)
        }

        // Assert
        rule.runOnIdle {
            assertThat(pagerState.currentPage).isEqualTo(DefaultPageCount - 1)
            assertThat(placed).doesNotContain(DefaultPageCount / 2 - 1)
            assertThat(placed).doesNotContain(DefaultPageCount / 2)
            assertThat(placed).doesNotContain(DefaultPageCount / 2 + 1)
        }
        confirmPageIsInCorrectPosition(pagerState.currentPage)
    }

    @Test
    fun animateScrollToPage_shouldPlacePagesCorrectly() = runBlocking {
        // Arrange

        createPager(modifier = Modifier.fillMaxSize())

        // Act and Assert
        repeat(DefaultAnimationRepetition) {
            assertThat(pagerState.currentPage).isEqualTo(it)
            withContext(Dispatchers.Main + AutoTestFrameClock()) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
            confirmPageIsInCorrectPosition(pagerState.currentPage)
        }
    }

    @Test
    fun animateScrollToPage_usedOffset_shouldPlacePagesCorrectly() = runBlocking {
        // Arrange

        suspend fun animateScrollToPageWithOffset(page: Int, offset: Float) {
            withContext(Dispatchers.Main + AutoTestFrameClock()) {
                pagerState.animateScrollToPage(page, offset)
            }
        }

        // Arrange
        createPager(modifier = Modifier.fillMaxSize())

        // Act
        animateScrollToPageWithOffset(10, 0.5f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 10, pageOffset = 0.5f)

        // Act
        animateScrollToPageWithOffset(4, 0.2f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 4, pageOffset = 0.2f)

        // Act
        animateScrollToPageWithOffset(12, -0.4f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 12, pageOffset = -0.4f)

        // Act
        animateScrollToPageWithOffset(DefaultPageCount - 1, 0.5f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, DefaultPageCount - 1)

        // Act
        animateScrollToPageWithOffset(0, -0.5f)

        // Assert
        confirmPageIsInCorrectPosition(pagerState.currentPage, 0)
    }

    @Test
    fun animateScrollToPage_longSkipShouldNotPlaceIntermediatePages() = runBlocking {
        // Arrange

        createPager(modifier = Modifier.fillMaxSize())

        // Act
        assertThat(pagerState.currentPage).isEqualTo(0)
        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            pagerState.animateScrollToPage(DefaultPageCount - 1)
        }

        // Assert
        rule.runOnIdle {
            assertThat(pagerState.currentPage).isEqualTo(DefaultPageCount - 1)
            assertThat(placed).doesNotContain(DefaultPageCount / 2 - 1)
            assertThat(placed).doesNotContain(DefaultPageCount / 2)
            assertThat(placed).doesNotContain(DefaultPageCount / 2 + 1)
        }
        confirmPageIsInCorrectPosition(pagerState.currentPage)
    }

    @Test
    fun scrollToPage_shouldCoerceWithinRange() = runBlocking {
        // Arrange

        createPager(modifier = Modifier.fillMaxSize())

        // Act
        assertThat(pagerState.currentPage).isEqualTo(0)
        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            pagerState.scrollToPage(DefaultPageCount)
        }

        // Assert
        rule.runOnIdle { assertThat(pagerState.currentPage).isEqualTo(DefaultPageCount - 1) }

        // Act
        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            pagerState.scrollToPage(-1)
        }

        // Assert
        rule.runOnIdle { assertThat(pagerState.currentPage).isEqualTo(0) }
    }

    @Test
    fun scrollToPage_usingLaunchedEffect() {

        createPager(additionalContent = {
            LaunchedEffect(pagerState) {
                pagerState.scrollToPage(10)
            }
        })
        rule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(10)
        confirmPageIsInCorrectPosition(10)
    }

    @Test
    fun scrollToPageWithOffset_usingLaunchedEffect() {
        createPager(additionalContent = {
            LaunchedEffect(pagerState) {
                pagerState.scrollToPage(10, 0.4f)
            }
        })
        rule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(10)
        confirmPageIsInCorrectPosition(10, pageOffset = 0.4f)
    }

    @Test
    fun animateScrollToPage_shouldCoerceWithinRange() = runBlocking {
        // Arrange

        createPager(modifier = Modifier.fillMaxSize())

        // Act
        assertThat(pagerState.currentPage).isEqualTo(0)
        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            pagerState.animateScrollToPage(DefaultPageCount)
        }

        // Assert
        rule.runOnIdle { assertThat(pagerState.currentPage).isEqualTo(DefaultPageCount - 1) }

        // Act
        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            pagerState.animateScrollToPage(-1)
        }

        // Assert
        rule.runOnIdle { assertThat(pagerState.currentPage).isEqualTo(0) }
    }

    @Test
    fun animateScrollToPage_moveToSamePageWithOffset_shouldScroll() = runBlocking {
        // Arrange
        createPager(initialPage = 5, modifier = Modifier.fillMaxSize())

        // Act
        assertThat(pagerState.currentPage).isEqualTo(5)

        withContext(Dispatchers.Main + AutoTestFrameClock()) {
            pagerState.animateScrollToPage(5, 0.4f)
        }

        // Assert
        rule.runOnIdle { assertThat(pagerState.currentPage).isEqualTo(5) }
        rule.runOnIdle { assertThat(pagerState.currentPageOffsetFraction).isWithin(0.01f).of(0.4f) }
    }

    @Test
    fun animateScrollToPage_withPassedAnimation() = runBlocking {
        // Arrange

        createPager(modifier = Modifier.fillMaxSize())
        val differentAnimation: AnimationSpec<Float> = tween()

        // Act and Assert
        repeat(DefaultAnimationRepetition) {
            assertThat(pagerState.currentPage).isEqualTo(it)
            withContext(Dispatchers.Main + AutoTestFrameClock()) {
                pagerState.animateScrollToPage(
                    pagerState.currentPage + 1,
                    animationSpec = differentAnimation
                )
            }
            confirmPageIsInCorrectPosition(pagerState.currentPage)
        }
    }

    @Test
    fun animatedScrollToPage_usingLaunchedEffect() {

        createPager(additionalContent = {
            LaunchedEffect(pagerState) {
                pagerState.animateScrollToPage(10)
            }
        })
        rule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(10)
        confirmPageIsInCorrectPosition(10)
    }

    @Test
    fun animatedScrollToPageWithOffset_usingLaunchedEffect() {

        createPager(additionalContent = {
            LaunchedEffect(pagerState) {
                pagerState.animateScrollToPage(10, 0.4f)
            }
        })
        rule.waitForIdle()
        assertThat(pagerState.currentPage).isEqualTo(10)
        confirmPageIsInCorrectPosition(10, pageOffset = 0.4f)
    }

    @Test
    fun animatedScrollToPage_viewPortNumberOfPages_usingLaunchedEffect_shouldNotPlaceALlPages() {

        createPager(additionalContent = {
            LaunchedEffect(pagerState) {
                pagerState.animateScrollToPage(DefaultPageCount - 1)
            }
        })
        rule.waitForIdle()
        // Assert
        rule.runOnIdle {
            assertThat(pagerState.currentPage).isEqualTo(DefaultPageCount - 1)
            assertThat(placed).doesNotContain(DefaultPageCount / 2 - 1)
            assertThat(placed).doesNotContain(DefaultPageCount / 2)
            assertThat(placed).doesNotContain(DefaultPageCount / 2 + 1)
        }
        confirmPageIsInCorrectPosition(pagerState.currentPage)
    }

    @Test
    fun currentPage_shouldChangeWhenClosestPageToSnappedPositionChanges() {
        // Arrange

        createPager(modifier = Modifier.fillMaxSize())
        var previousCurrentPage = pagerState.currentPage

        // Act
        // Move less than half an item
        val firstDelta = (pagerSize * 0.4f) * scrollForwardSign
        onPager().performTouchInput {
            down(layoutStart)
            if (vertical) {
                moveBy(Offset(0f, firstDelta))
            } else {
                moveBy(Offset(firstDelta, 0f))
            }
        }

        // Assert
        rule.runOnIdle {
            assertThat(pagerState.currentPage).isEqualTo(previousCurrentPage)
        }
        // Release pointer
        onPager().performTouchInput { up() }

        rule.runOnIdle {
            previousCurrentPage = pagerState.currentPage
        }
        confirmPageIsInCorrectPosition(pagerState.currentPage)

        // Arrange
        // Pass closest to snap position threshold (over half an item)
        val secondDelta = (pagerSize * 0.6f) * scrollForwardSign

        // Act
        onPager().performTouchInput {
            down(layoutStart)
            if (vertical) {
                moveBy(Offset(0f, secondDelta))
            } else {
                moveBy(Offset(secondDelta, 0f))
            }
        }

        // Assert
        rule.runOnIdle {
            assertThat(pagerState.currentPage).isEqualTo(previousCurrentPage + 1)
        }

        onPager().performTouchInput { up() }
        rule.waitForIdle()
        confirmPageIsInCorrectPosition(pagerState.currentPage)
    }

    @Test
    fun targetPage_performScrollBelowMinThreshold_shouldNotShowNextPage() {
        // Arrange
        createPager(
            modifier = Modifier.fillMaxSize(),
            snappingPage = PagerSnapDistance.atMost(3)
        )
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }

        rule.mainClock.autoAdvance = false
        // Act
        // Moving less than threshold
        val forwardDelta =
            scrollForwardSign.toFloat() * with(rule.density) { DefaultPositionThreshold.toPx() / 2 }

        var previousTargetPage = pagerState.targetPage

        onPager().performTouchInput {
            down(layoutStart)
            moveBy(Offset(forwardDelta, forwardDelta))
        }

        // Assert
        assertThat(pagerState.targetPage).isEqualTo(previousTargetPage)

        // Reset
        rule.mainClock.autoAdvance = true
        onPager().performTouchInput { up() }
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }

        // Act
        // Moving more than threshold
        val backwardDelta = scrollForwardSign.toFloat() * with(rule.density) {
            -DefaultPositionThreshold.toPx() / 2
        }

        previousTargetPage = pagerState.targetPage

        onPager().performTouchInput {
            down(layoutStart)
            moveBy(Offset(backwardDelta, backwardDelta))
        }

        // Assert
        assertThat(pagerState.targetPage).isEqualTo(previousTargetPage)
    }

    @Test
    fun targetPage_performScroll_shouldShowNextPage() {
        // Arrange
        createPager(
            modifier = Modifier.fillMaxSize(),
            snappingPage = PagerSnapDistance.atMost(3)
        )
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }

        rule.mainClock.autoAdvance = false
        // Act
        // Moving forward
        val forwardDelta = pagerSize * 0.4f * scrollForwardSign.toFloat()
        onPager().performTouchInput {
            down(layoutStart)
            moveBy(Offset(forwardDelta, forwardDelta))
        }

        // Assert
        assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage + 1)
        assertThat(pagerState.targetPage).isNotEqualTo(pagerState.currentPage)

        // Reset
        rule.mainClock.autoAdvance = true
        onPager().performTouchInput { up() }
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }
        rule.runOnIdle {
            runBlocking { pagerState.scrollToPage(5) }
        }

        rule.mainClock.autoAdvance = false
        // Act
        // Moving backward
        val backwardDelta = -pagerSize * 0.4f * scrollForwardSign.toFloat()
        onPager().performTouchInput {
            down(layoutEnd)
            moveBy(Offset(backwardDelta, backwardDelta))
        }

        // Assert
        assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage - 1)
        assertThat(pagerState.targetPage).isNotEqualTo(pagerState.currentPage)

        rule.mainClock.autoAdvance = true
        onPager().performTouchInput { up() }
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }
    }

    @Test
    fun targetPage_performingFling_shouldGoToPredictedPage() {
        // Arrange

        createPager(
            modifier = Modifier.fillMaxSize(),
            snappingPage = PagerSnapDistance.atMost(3)
        )
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }

        rule.mainClock.autoAdvance = false
        // Act
        // Moving forward
        var previousTarget = pagerState.targetPage
        val forwardDelta = pagerSize * scrollForwardSign.toFloat()
        onPager().performTouchInput {
            swipeWithVelocityAcrossMainAxis(20000f, forwardDelta)
        }
        rule.mainClock.advanceTimeUntil { pagerState.targetPage != previousTarget }
        var flingOriginIndex = pagerState.firstVisiblePage
        // Assert
        assertThat(pagerState.targetPage).isEqualTo(flingOriginIndex + 3)
        assertThat(pagerState.targetPage).isNotEqualTo(pagerState.currentPage)

        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }
        rule.mainClock.autoAdvance = false
        // Act
        // Moving backward
        previousTarget = pagerState.targetPage
        val backwardDelta = -pagerSize * scrollForwardSign.toFloat()
        onPager().performTouchInput {
            swipeWithVelocityAcrossMainAxis(20000f, backwardDelta)
        }
        rule.mainClock.advanceTimeUntil { pagerState.targetPage != previousTarget }

        // Assert
        flingOriginIndex = pagerState.firstVisiblePage + 1
        assertThat(pagerState.targetPage).isEqualTo(flingOriginIndex - 3)
        assertThat(pagerState.targetPage).isNotEqualTo(pagerState.currentPage)

        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }
    }

    @Test
    fun targetPage_shouldReflectTargetWithAnimation() {
        // Arrange

        createPager(
            modifier = Modifier.fillMaxSize()
        )
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }

        rule.mainClock.autoAdvance = false
        // Act
        // Moving forward
        var previousTarget = pagerState.targetPage
        rule.runOnIdle {
            scope.launch {
                pagerState.animateScrollToPage(DefaultPageCount - 1)
            }
        }
        rule.mainClock.advanceTimeUntil { pagerState.targetPage != previousTarget }

        // Assert
        assertThat(pagerState.targetPage).isEqualTo(DefaultPageCount - 1)
        assertThat(pagerState.targetPage).isNotEqualTo(pagerState.currentPage)

        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }
        rule.mainClock.autoAdvance = false

        // Act
        // Moving backward
        previousTarget = pagerState.targetPage
        rule.runOnIdle {
            scope.launch {
                pagerState.animateScrollToPage(0)
            }
        }
        rule.mainClock.advanceTimeUntil { pagerState.targetPage != previousTarget }

        // Assert
        assertThat(pagerState.targetPage).isEqualTo(0)
        assertThat(pagerState.targetPage).isNotEqualTo(pagerState.currentPage)

        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertThat(pagerState.targetPage).isEqualTo(pagerState.currentPage) }
    }

    @Test
    fun settledPage_onAnimationScroll_shouldChangeOnScrollFinishedOnly() {
        // Arrange
        var settledPageChanges = 0
        createPager(
            modifier = Modifier.fillMaxSize(),
            additionalContent = {
                LaunchedEffect(key1 = pagerState.settledPage) {
                    settledPageChanges++
                }
            }
        )

        // Settle page changed once for first composition
        rule.runOnIdle {
            assertThat(pagerState.settledPage).isEqualTo(pagerState.currentPage)
            assertTrue { settledPageChanges == 1 }
        }

        settledPageChanges = 0
        val previousSettled = pagerState.settledPage
        rule.mainClock.autoAdvance = false
        // Act
        // Moving forward
        rule.runOnIdle {
            scope.launch {
                pagerState.animateScrollToPage(DefaultPageCount - 1)
            }
        }

        // Settled page shouldn't change whilst scroll is in progress.
        assertTrue { pagerState.isScrollInProgress }
        assertTrue { settledPageChanges == 0 }
        assertThat(pagerState.settledPage).isEqualTo(previousSettled)

        rule.mainClock.advanceTimeUntil { settledPageChanges != 0 }

        rule.runOnIdle {
            assertTrue { !pagerState.isScrollInProgress }
            assertThat(pagerState.settledPage).isEqualTo(pagerState.currentPage)
        }
    }

    @Test
    fun settledPage_onGestureScroll_shouldChangeOnScrollFinishedOnly() {
        // Arrange
        var settledPageChanges = 0
        createPager(
            modifier = Modifier.fillMaxSize(),
            additionalContent = {
                LaunchedEffect(key1 = pagerState.settledPage) {
                    settledPageChanges++
                }
            }
        )

        settledPageChanges = 0
        val previousSettled = pagerState.settledPage
        rule.mainClock.autoAdvance = false
        // Act
        // Moving forward
        val forwardDelta = pagerSize / 2f * scrollForwardSign.toFloat()
        rule.onNodeWithTag(PagerTestTag).performTouchInput {
            swipeWithVelocityAcrossMainAxis(10000f, forwardDelta)
        }

        // Settled page shouldn't change whilst scroll is in progress.
        assertTrue { pagerState.isScrollInProgress }
        assertTrue { settledPageChanges == 0 }
        assertThat(pagerState.settledPage).isEqualTo(previousSettled)

        rule.mainClock.advanceTimeUntil { settledPageChanges != 0 }

        rule.runOnIdle {
            assertTrue { !pagerState.isScrollInProgress }
            assertThat(pagerState.settledPage).isEqualTo(pagerState.currentPage)
        }
    }

    @Test
    fun currentPageOffset_shouldReflectScrollingOfCurrentPage() {
        // Arrange
        createPager(initialPage = DefaultPageCount / 2, modifier = Modifier.fillMaxSize())

        // No offset initially
        rule.runOnIdle {
            assertThat(pagerState.currentPageOffsetFraction).isWithin(0.01f).of(0f)
        }

        // Act
        // Moving forward
        onPager().performTouchInput {
            down(layoutStart)
            if (vertical) {
                moveBy(Offset(0f, scrollForwardSign * pagerSize / 4f))
            } else {
                moveBy(Offset(scrollForwardSign * pagerSize / 4f, 0f))
            }
        }

        rule.runOnIdle {
            assertThat(pagerState.currentPageOffsetFraction).isWithin(0.1f).of(0.25f)
        }

        onPager().performTouchInput { up() }
        rule.waitForIdle()

        // Reset
        rule.runOnIdle {
            scope.launch {
                pagerState.scrollToPage(DefaultPageCount / 2)
            }
        }

        // No offset initially (again)
        rule.runOnIdle {
            assertThat(pagerState.currentPageOffsetFraction).isWithin(0.01f).of(0f)
        }

        // Act
        // Moving backward
        onPager().performTouchInput {
            down(layoutStart)
            if (vertical) {
                moveBy(Offset(0f, -scrollForwardSign * pagerSize / 4f))
            } else {
                moveBy(Offset(-scrollForwardSign * pagerSize / 4f, 0f))
            }
        }

        rule.runOnIdle {
            assertThat(pagerState.currentPageOffsetFraction).isWithin(0.1f).of(-0.25f)
        }
    }

    @Test
    fun initialPageOnPagerState_shouldDisplayThatPageFirst() {
        // Arrange

        // Act
        createPager(initialPage = 5, modifier = Modifier.fillMaxSize())

        // Assert
        rule.onNodeWithTag("4").assertDoesNotExist()
        rule.onNodeWithTag("5").assertIsDisplayed()
        rule.onNodeWithTag("6").assertDoesNotExist()
        confirmPageIsInCorrectPosition(pagerState.currentPage)
    }

    @Test
    fun testStateRestoration() {
        // Arrange
        val tester = StateRestorationTester(rule)
        lateinit var state: PagerState
        tester.setContent {
            state = rememberPagerState(pageCount = { DefaultPageCount })
            scope = rememberCoroutineScope()
            HorizontalOrVerticalPager(
                state = state,
                modifier = Modifier.fillMaxSize()
            ) {
                Page(it)
            }
        }

        // Act
        rule.runOnIdle {
            scope.launch {
                state.scrollToPage(5)
            }
            runBlocking {
                state.scroll {
                    scrollBy(50f)
                }
            }
        }

        val previousPage = state.currentPage
        val previousOffset = state.currentPageOffsetFraction
        tester.emulateSavedInstanceStateRestore()

        // Assert
        rule.runOnIdle {
            assertThat(state.currentPage).isEqualTo(previousPage)
            assertThat(state.currentPageOffsetFraction).isEqualTo(previousOffset)
        }
    }

    @Test
    fun scrollTo_beforeFirstLayout_shouldWaitForStateAndLayoutSetting() {
        // Arrange

        rule.mainClock.autoAdvance = false

        // Act
        createPager(modifier = Modifier.fillMaxSize(), additionalContent = {
            LaunchedEffect(pagerState) {
                pagerState.scrollToPage(5)
            }
        })

        // Assert
        assertThat(pagerState.currentPage).isEqualTo(5)
    }

    @Test
    fun currentPageOffsetFraction_shouldNeverBeNan() {
        rule.setContent {
            val state = rememberPagerState(pageCount = { 10 })
            // Read state in composition, should never be Nan
            assertFalse { state.currentPageOffsetFraction.isNaN() }
            HorizontalOrVerticalPager(state = state) {
                Page(index = it)
            }
        }
    }

    @Test
    fun calculatePageCountOffset_shouldBeBasedOnCurrentPage() {
        val pageToOffsetCalculations = mutableMapOf<Int, Float>()
        createPager(modifier = Modifier.fillMaxSize(), pageSize = { PageSize.Fixed(20.dp) }) {
            pageToOffsetCalculations[it] = pagerState.getOffsetFractionForPage(it)
            Page(index = it)
        }

        for ((page, offset) in pageToOffsetCalculations) {
            val currentPage = pagerState.currentPage
            val currentPageOffset = pagerState.currentPageOffsetFraction
            assertThat(offset).isEqualTo((currentPage - page) + currentPageOffset)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = mutableListOf<ParamConfig>().apply {
            for (orientation in TestOrientation) {
                for (reverseLayout in TestReverseLayout) {
                    for (layoutDirection in TestLayoutDirection) {
                        add(
                            ParamConfig(
                                orientation = orientation,
                                reverseLayout = reverseLayout,
                                layoutDirection = layoutDirection
                            )
                        )
                    }
                }
            }
        }
    }
}