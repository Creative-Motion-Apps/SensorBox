package com.motionapps.wearoslib

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityClient.OnCapabilityChangedListener
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.lang.Exception
import java.util.concurrent.ExecutionException

/**
 * Background to search for the Wear Os and send messages
 *
 */
class WearOsHandler {

    var nodeId: String? = null
    private var capability: String? = null
    private var msg: String = ""
    private var wearOsListener: WearOsListener? = null

    /**
     *  Tries to find Node by which, the messages can be sent
     *
     * @param context
     * @param wearOsListener - object to which the response will be sent
     * @param capability - Wearable or phone capability from XML
     */
    suspend fun searchForWearOs(
        context: Context,
        wearOsListener: WearOsListener,
        capability: String
    ) {
        val client = Wearable.getCapabilityClient(context)
        this.wearOsListener = wearOsListener
        this.capability = capability
        try {
            searchForNode(client)
            setupListener(client)
        } catch (e: ExecutionException) {
            e.printStackTrace()
            wearOsListener.onWearOsStates(WearOsStates.PresenceResult(false))
        } catch (e: InterruptedException) {
            e.printStackTrace()
            wearOsListener.onWearOsStates(WearOsStates.PresenceResult(false))
        }
    }

    /**
     * Uses RunBlocking to send message - avoid to put it into UI thread ot the activity
     *
     * @param context
     * @param capability - Wearable or phone capability from XML
     * @param path - path to use to send message
     * @param msg - string of the message
     * @return
     */
    fun sendMessageInstant(
        context: Context,
        capability: String,
        path: String,
        msg: String
    ): Boolean {
        val client = Wearable.getCapabilityClient(context)
        this.capability = capability
        this.msg = msg
        return runBlocking {
            try {
                val b = searchForNode(client)
                if (b) {
                    sendMsg(context, path, msg)
                }
                return@runBlocking b
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            return@runBlocking false
        }

    }


    /**
     * Searches for the node in asynchronous call - need to wait for the result
     *
     * @param client
     * @return
     */
    @Throws(ExecutionException::class, InterruptedException::class)
    private suspend fun searchForNode(client: CapabilityClient): Boolean {
        return withContext(Dispatchers.IO) {
            val j = async { getCapabilities(client) } // searching for wear nodes
            j.await()?.let {
                updateCapability(it) // update nodeId
                return@withContext true
            } ?: run {
                // no node
                wearOsListener?.onWearOsStates(WearOsStates.PresenceResult(false))
                return@withContext false
            }
        }


    }

    /**
     * @param client
     * @return list of nodes
     */
    private fun getCapabilities(client: CapabilityClient): CapabilityInfo? {
        return try {
            Tasks.await(client.getCapability(capability!!, CapabilityClient.FILTER_REACHABLE))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    }

    /**
     * Listens to changes of nodes -> sends them by callback
     *
     * @param client
     */
    private fun setupListener(client: CapabilityClient) {
        val capabilityListener = OnCapabilityChangedListener { capabilityInfo: CapabilityInfo ->
            CoroutineScope(Dispatchers.Main).launch {
                updateCapability(capabilityInfo)
                wearOsListener?.onWearOsStates(WearOsStates.PresenceResult(isNode))
            }
        }
        client.addListener(
            capabilityListener,
            capability!!
        )
    }

    /**
     * search for viable node to send info to connect
     * @param capabilityInfo - output of the WearableClient
     */
    private suspend fun updateCapability(capabilityInfo: CapabilityInfo): String? {
        val connectedNodes = capabilityInfo.nodes
        nodeId = pickBestNodeId(connectedNodes)
        if (nodeId == "") {
            wearOsListener?.onWearOsStates(WearOsStates.PresenceResult(false))
        } else { // broadcasting to first node which is available
            wearOsListener?.onWearOsStates(WearOsStates.PresenceResult(true))
        }
        return nodeId
    }

    /**
     *  Find a nearby node or pick one arbitrarily
     *
     * @param nodes - list of available nodes
     * @return
     */
    private fun pickBestNodeId(nodes: Set<Node>): String {
        var bestNodeId = ""

        for (node in nodes) {
            if (node.isNearby) {
                return node.id
            }
            bestNodeId = node.id
        }
        return bestNodeId
    }

    val isNode: Boolean
        get() = nodeId != null && nodeId != ""

    /**
     * Sends message via nodeId to Wearable device
     *
     * @param context
     * @param path - to use for device
     * @param msg - to send
     * @param silent - true - if the error occurs, the Toast is raised to user
     */
    fun sendMsg(context: Context, path: String, msg: String, silent: Boolean = false) {
        nodeId?.let { nodeString ->
            val sendTask =
                Wearable.getMessageClient(context)?.sendMessage(nodeString, path, msg.toByteArray())
            sendTask?.addOnSuccessListener {
                Log.i(TAG, "sendMsg: Successful $msg")
            }
            sendTask?.addOnFailureListener {
                if (silent) {
                    Toast.makeText(
                        context,
                        R.string.sync_failed_restart,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                it.printStackTrace()
            }
        }

    }

    fun onDestroy(){
        wearOsListener = null
    }

    companion object {
        private const val TAG = "WEAR"
    }
}