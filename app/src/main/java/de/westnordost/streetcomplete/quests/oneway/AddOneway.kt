package de.westnordost.streetcomplete.quests.oneway

import de.westnordost.osmapi.map.MapDataWithGeometry
import de.westnordost.osmapi.map.data.Element
import de.westnordost.osmapi.map.data.Way
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.elementfilter.ElementFiltersParser
import de.westnordost.streetcomplete.data.meta.ALL_ROADS
import de.westnordost.streetcomplete.data.osm.changes.StringMapChangesBuilder
import de.westnordost.streetcomplete.quests.bikeway.createCyclewaySides
import de.westnordost.streetcomplete.quests.bikeway.estimatedWidth
import de.westnordost.streetcomplete.data.osm.osmquest.OsmMapDataQuestType
import de.westnordost.streetcomplete.quests.oneway.OnewayAnswer.*
import de.westnordost.streetcomplete.quests.parking_lanes.*

class AddOneway : OsmMapDataQuestType<OnewayAnswer> {

    /** find all roads */
    private val allRoadsFilter by lazy { ElementFiltersParser().parse("""
        ways with highway ~ ${ALL_ROADS.joinToString("|")} and area != yes
    """) }

    /** find only those roads eligible for asking for oneway */
    private val elementFilter by lazy { ElementFiltersParser().parse("""
        ways with highway ~ living_street|residential|service|tertiary|unclassified
         and !oneway and area != yes and junction != roundabout 
         and (access !~ private|no or (foot and foot !~ private|no))
         and lanes <= 1 and width
    """) }

    override val commitMessage = "Add whether this road is a one-way road because it is quite slim"
    override val wikiLink = "Key:oneway"
    override val icon = R.drawable.ic_quest_oneway
    override val hasMarkersAtEnds = true
    override val isSplitWayEnabled = true

    override fun getTitle(tags: Map<String, String>) = R.string.quest_oneway2_title

    override fun getApplicableElements(mapData: MapDataWithGeometry): List<Element> {
        val allRoads = mapData.ways.filter { allRoadsFilter.matches(it) && it.nodeIds.size >= 2 }
        val connectionCountByNodeIds = mutableMapOf<Long, Int>()
        val onewayCandidates = mutableListOf<Way>()

        for (road in allRoads) {
            for (nodeId in road.nodeIds) {
                val prevCount = connectionCountByNodeIds[nodeId] ?: 0
                connectionCountByNodeIds[nodeId] = prevCount + 1
            }
            if (elementFilter.matches(road)) {
                // check if the width of the road minus the space consumed by parking lanes is quite narrow
                val width = road.tags["width"]?.toFloatOrNull()
                val isNarrow = width != null && width <= estimatedWidthConsumedByOtherThings(road.tags) + 4f
                if (isNarrow) {
                    onewayCandidates.add(road)
                }
            }
        }

        return onewayCandidates.filter {
            /* ways that are simply at the border of the download bounding box are treated as if
               they are dead ends. This is fine though, because it only leads to this quest not
               showing up for those streets (which is better than the other way round)
            */
            // check if the way has connections to other roads at both ends
            (connectionCountByNodeIds[it.nodeIds.first()] ?: 0) > 1 &&
            (connectionCountByNodeIds[it.nodeIds.last()] ?: 0) > 1
        }
    }

    override fun isApplicableTo(element: Element): Boolean? = null
    /* return null because we also want to have a look at the surrounding geometry to filter out
    *  (some) dead ends */

    private fun estimatedWidthConsumedByOtherThings(tags: Map<String, String>): Float {
        return estimateWidthConsumedByParkingLanes(tags) +
                estimateWidthConsumedByCycleLanes(tags)
    }

    private fun estimateWidthConsumedByParkingLanes(tags: Map<String, String>): Float {
        val sides = createParkingLaneSides(tags) ?: return 0f
        return (sides.left?.estimatedWidth ?: 0f) + (sides.right?.estimatedWidth ?: 0f)
    }

    private fun estimateWidthConsumedByCycleLanes(tags: Map<String, String>): Float {
        /* left or right hand traffic is irrelevant here because we don't make a difference between
           left and right side */
        val sides = createCyclewaySides(tags, false) ?: return 0f
        return (sides.left?.estimatedWidth ?: 0f) + (sides.left?.estimatedWidth ?: 0f)
    }

    override fun createForm() = AddOnewayForm()

    override fun applyAnswerTo(answer: OnewayAnswer, changes: StringMapChangesBuilder) {
        changes.add("oneway", when(answer) {
            FORWARD -> "yes"
            BACKWARD -> "-1"
            NO_ONEWAY -> "no"
        })
    }
}
