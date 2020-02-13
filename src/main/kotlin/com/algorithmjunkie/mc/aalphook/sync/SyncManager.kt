package com.algorithmjunkie.mc.aalphook.sync

import com.algorithmjunkie.mc.aalphook.AALPPlugin
import com.algorithmjunkie.mc.aalphook.hook.HookActionType
import com.algorithmjunkie.mc.aalphook.man.asLpUser
import com.algorithmjunkie.mc.aalphook.man.hasLpGroup
import com.algorithmjunkie.mc.aalphook.man.minimallyHasLpGroup
import com.okkero.skedule.SynchronizationContext
import com.okkero.skedule.schedule
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.group.Group
import net.luckperms.api.node.Node
import org.bukkit.entity.Player

class SyncManager(private val plugin: AALPPlugin) {
    fun synchronize(player: Player) {
        val plugin = plugin
        plugin.schedule(SynchronizationContext.ASYNC) {
            val playerDisplayName = player.displayName

            plugin.log.dbg("Running hooks for player $playerDisplayName")
            for (hook in plugin.hooks.loadedHooks) {
                plugin.log.dbg("Running hook $hook for $playerDisplayName")

                val requiredGroup = hook.requiredGroup
                if (requiredGroup.isApplicable()) {
                    val lpGroup = plugin.apis.getLpGroup(requiredGroup.name!!)
                    if (lpGroup == null) {
                        plugin.log.dbg("Failed to load LP Group Object for group ${requiredGroup.name}; hook will be skipped!")
                        continue
                    }

                    if (requiredGroup.isTrackBased()) {
                        plugin.log.dbg("Hook ${hook.name} minimally requires LP group ${requiredGroup.name} and is based on track ${requiredGroup.track}")
                        plugin.log.dbg("Checking if player $playerDisplayName minimally has group or higher group in track for hook ${hook.name}")

                        if (!player.minimallyHasLpGroup(lpGroup, requiredGroup.track!!)) {
                            plugin.log.dbg("Player $playerDisplayName did not have minimally required group or any higher group in track for hook ${hook.name}")
                            continue
                        }

                        plugin.log.dbg("Player $playerDisplayName either had minimally required group or higher group in track; hook processing will proceed")
                    } else {
                        plugin.log.dbg("Hook ${hook.name} strictly requires LP group ${requiredGroup.name} and is not track based")
                        plugin.log.dbg("Checking if player $playerDisplayName has required group for hook ${hook.name}")

                        if (!player.hasLpGroup(lpGroup)) {
                            plugin.log.dbg("Player $playerDisplayName did not have the required group for ${hook.name}")
                            continue
                        }

                        plugin.log.dbg("Player $playerDisplayName did have the required group for ${hook.name}; hook processing will proceed.")
                    }
                }

                plugin.log.dbg("Checking required achievement criteria for hook ${hook.name}")
                val meetsRequiredAchievementCriteria = hook.requiredAchievements.all {
                    plugin.log.dbg("Checking $playerDisplayName has completed achievement $it")
                    val completed = plugin.apis.achievements.hasPlayerReceivedAchievement(player.uniqueId, it)
                    plugin.log.dbg("User has ${if (completed) "" else "not"} completed achievement $it")
                    completed
                }

                if (meetsRequiredAchievementCriteria) {
                    plugin.log.dbg("Hook ${hook.name} achievement criteria was met by $playerDisplayName; hook processing will proceed.")

                    for (action in hook.thenApplyActions) {
                        plugin.log.dbg("Running ${hook.name} action $action for $playerDisplayName")

                        val target = action.value

                        val result = when (action.key) {
                            HookActionType.ADDGROUP -> doAddGroup(player, target)
                            HookActionType.DELGROUP -> doDelGroup(player, target)
                            HookActionType.ADDPERM -> doAddPerm(player, target)
                            HookActionType.DELPERM -> doDelPerm(player, target)
                        }

                        plugin.log.dbg("Processing result for hook ${hook.name} action $action")
                        when (result.first) {
                            true -> {
                                when (result.second) {
                                    "added" -> plugin.log.inf("${player.displayName} was added to group or permission $target")
                                    "removed" -> plugin.log.inf("${player.displayName} was removed from group or permission $target")
                                }

                                if (hook.thenSendMessages.isNotEmpty()) {
                                    hook.thenSendMessages.forEach { message -> player.sendMessage(message) }
                                }
                            }

                            false -> {
                                when (result.second) {
                                    "in_group" -> plugin.log.wrn("LuckPerms reported user ${player.displayName} was already in group $target")
                                    "not_in_group" -> plugin.log.wrn("LuckPerms reported user ${player.displayName} was not already in group $target")
                                    "unknown_err" -> plugin.log.sev("LuckPerms reported an unknown error with the previously requested action.")
                                    "not_found" -> plugin.log.sev("The target group $target could not be found.")
                                }
                            }
                        }
                    }

                    plugin.log.dbg("Synchronizing changes with LuckPerms...")
                    plugin.apis.permissions.userManager.saveUser(player.asLpUser())
                    plugin.log.dbg("Done!")
                }
            }
        }
    }

    private fun resolveGroup(group: String): Group? {
        val resolved = plugin.apis.permissions.groupManager.getGroup(group)

        if (resolved == null) {
            plugin.log.dbg("Could not resolve LP Group Object referenced by hook!")
            return null
        }

        return resolved
    }

    private fun doAddGroup(player: Player, group: String): Pair<Boolean, String> {
        val resolved = resolveGroup(group) ?: return false to "not_found"

        if (player.hasLpGroup(resolved)) {
            return false to "in_group"
        }

        return doAddPerm(player, "group.${group}")
    }

    private fun doAddPerm(player: Player, node: String): Pair<Boolean, String> {
        return doAddPerm(player, Node.builder(node).build())
    }

    private fun doAddPerm(player: Player, node: Node): Pair<Boolean, String> {
        val result: DataMutateResult = player.asLpUser().data().add(node)
        return if (result.wasSuccessful()) {
            true to "added"
        } else {
            false to "unknown_err"
        }
    }

    private fun doDelGroup(player: Player, group: String): Pair<Boolean, String> {
        val resolved = resolveGroup(group) ?: return false to "not_found"

        if (!player.hasLpGroup(resolved)) {
            return false to "not_in_group"
        }

        return doDelPerm(player, "group.${group}")
    }

    private fun doDelPerm(player: Player, node: String): Pair<Boolean, String> {
        return doDelPerm(player, Node.builder(node).build())
    }

    private fun doDelPerm(player: Player, node: Node): Pair<Boolean, String> {
        val result = player.asLpUser().data().remove(node)
        return if (result.wasSuccessful()) {
            true to "removed"
        } else {
            false to "unknown_err"
        }
    }
}