package kotlinx.dnq.events

import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.entitystore.EventsMultiplexer
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.listener.removeListener
import kotlinx.dnq.query.size
import kotlinx.dnq.transactional
import kotlinx.dnq.util.getAddedLinks
import kotlinx.dnq.util.hasChanges
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class EventsTest : DBTest() {

    @Before
    fun updateMultiplexer() {
        store.eventsMultiplexer = EventsMultiplexer(createAsyncProcessor().apply(JobProcessor::start))
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo, Goo)
    }

    @Test
    fun accessAddedOnUpdatedAfterGetLinks() {
        val (f1, f2, g) = store.transactional {
            Triple(Foo.new(), Foo.new(), Goo.new())
        }
        val contentChanged = AtomicBoolean(false)
        val contentsAdded = AtomicBoolean(false)
        val listener = object : XdEntityListener<Goo> {
            override fun updatedSync(old: Goo, current: Goo) {
                contentChanged.set(old.hasChanges(Goo::content))
                contentsAdded.set(old.getAddedLinks(Goo::content).size() == 2)
            }
        }
        store.eventsMultiplexer.addListener(Goo, listener)
        try {
            store.transactional {
                g.content.add(f1)
                g.content.add(f2)
            }
            assertTrue(contentChanged.get())
            assertTrue(contentsAdded.get())
        } finally {
            store.eventsMultiplexer.removeListener(Goo, listener)
        }
    }

}