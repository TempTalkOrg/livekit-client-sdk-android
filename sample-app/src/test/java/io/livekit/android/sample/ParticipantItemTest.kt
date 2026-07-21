/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.sample

import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.Participant.Identity
import io.livekit.android.room.participant.Participant.Sid
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.databinding.ParticipantItemBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class ParticipantItemTest {

    private val room = mock<Room>()
    private val mainRenderer = mock<TextureViewRenderer>()
    private val cameraRenderer = mock<TextureViewRenderer>()
    private val binding = createBinding(mainRenderer, cameraRenderer)
    private val dispatcher = UnconfinedTestDispatcher()
    private val participant = Participant(Sid("sid"), Identity("identity"), dispatcher)
    private lateinit var item: ParticipantItem

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        item = ParticipantItem(room, participant)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bindInitializesBothRenderers() {
        item.bind(binding, 0)

        verify(room).initVideoRenderer(mainRenderer)
        verify(room).initVideoRenderer(cameraRenderer)

        item.unbind(GroupieViewHolder(binding))
    }

    @Test
    fun repeatedBindDoesNotReinitializeRenderers() {
        item.bind(binding, 0)
        item.bind(binding, 0)

        verify(room, times(1)).initVideoRenderer(mainRenderer)
        verify(room, times(1)).initVideoRenderer(cameraRenderer)

        item.unbind(GroupieViewHolder(binding))
    }

    @Test
    fun unbindRemovesTracksBeforeReleasingRenderers() {
        val mainTrack = mock<VideoTrack>()
        val cameraTrack = mock<VideoTrack>()
        item.bind(binding, 0)
        setBoundTrack("boundMainVideoTrack", mainTrack)
        setBoundTrack("boundCameraVideoTrack", cameraTrack)

        item.unbind(GroupieViewHolder(binding))

        inOrder(mainTrack, mainRenderer) {
            verify(mainTrack).removeRenderer(mainRenderer)
            verify(mainRenderer).release()
        }
        inOrder(cameraTrack, cameraRenderer) {
            verify(cameraTrack).removeRenderer(cameraRenderer)
            verify(cameraRenderer).release()
        }
    }

    @Test
    fun repeatedUnbindDoesNotReleaseRenderersTwice() {
        item.bind(binding, 0)
        val holder = GroupieViewHolder(binding)

        item.unbind(holder)
        item.unbind(holder)

        verify(mainRenderer, times(1)).release()
        verify(cameraRenderer, times(1)).release()
    }

    @Test
    fun switchingBindingTearsDownOldHolderAndMakesItsLaterUnbindANoOp() {
        val secondMainRenderer = mock<TextureViewRenderer>()
        val secondCameraRenderer = mock<TextureViewRenderer>()
        val secondBinding = createBinding(secondMainRenderer, secondCameraRenderer)
        val mainTrack = mock<VideoTrack>()
        item.bind(binding, 0)
        setBoundTrack("boundMainVideoTrack", mainTrack)

        item.bind(secondBinding, 1)
        item.unbind(GroupieViewHolder(binding))

        verify(mainTrack).removeRenderer(mainRenderer)
        verify(mainRenderer, times(1)).release()
        verify(cameraRenderer, times(1)).release()
        verify(secondMainRenderer, times(0)).release()
        verify(secondCameraRenderer, times(0)).release()

        item.unbind(GroupieViewHolder(secondBinding))
    }

    private fun setBoundTrack(fieldName: String, track: VideoTrack) {
        val field = ParticipantItem::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(item, track)
    }

    private fun createBinding(
        mainRenderer: TextureViewRenderer,
        cameraRenderer: TextureViewRenderer,
    ): ParticipantItemBinding {
        val constructor = ParticipantItemBinding::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(
            mock<ConstraintLayout>(),
            cameraRenderer,
            mock<ImageView>(),
            mock<FrameLayout>(),
            mock<TextView>(),
            mock<ImageView>(),
            mainRenderer,
            mock<ImageView>(),
        ) as ParticipantItemBinding
    }
}
