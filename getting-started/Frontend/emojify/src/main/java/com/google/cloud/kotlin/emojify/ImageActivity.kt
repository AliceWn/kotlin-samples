/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.kotlin.emojify

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.util.NoSuchPropertyException
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.yanzhenjie.album.Album
import com.yanzhenjie.album.AlbumFile
import com.yanzhenjie.album.api.widget.Widget
import com.yanzhenjie.album.impl.OnItemClickListener
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.yanzhenjie.album.widget.divider.Api21ItemDivider
import kotlinx.android.synthetic.main.activity_list_content.image_view
import kotlinx.android.synthetic.main.activity_list_content.tv_message
import kotlinx.android.synthetic.main.activity_list_content.recycler_view
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.android.synthetic.main.toolbar.toolbar
import java.io.File
import java.util.ArrayList
import java.util.Properties

class ImageActivity : AppCompatActivity() {

    private lateinit var backendUrl: String
    private var adapter: Adapter? = null
    private var albumFiles: ArrayList<AlbumFile>? = null
    private val job = Job()

    companion object {
        private var emjojifiedUrl: String = ""
        private var imageId: String = ""
        private val storageRef: StorageReference = FirebaseStorage.getInstance().reference
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        show("First, select picture to emojify!")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album)
        setSupportActionBar(toolbar)
        recycler_view.layoutManager = GridLayoutManager(this, 3) as RecyclerView.LayoutManager
        val divider = Api21ItemDivider(Color.TRANSPARENT, 10, 10)
        recycler_view.addItemDecoration(divider)
        adapter = Adapter(this, OnItemClickListener { _, position -> previewImage(position) })
        recycler_view.adapter = adapter

        val properties = Properties()
        properties.load(assets.open("app.properties"))
        if (properties["storage.bucket.name"] == null)
            throw NoSuchPropertyException("property 'storage.bucket.name' doesn't exist in app.properties!")

        backendUrl = "https://${properties["storage.bucket.name"]}"
        selectImage()
    }

    private fun show(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun updateUI(fct: () -> Unit) = this.runOnUiThread(java.lang.Runnable(fct))

    private fun callEmojifyBackend() {
        val queue = Volley.newRequestQueue(this)
        val url = "${this.backendUrl}/emojify?objectName=$imageId"
        updateUI { show("Image uploaded to Storage!") }
        val request = JsonObjectRequest(Request.Method.GET, url, null,
                Response.Listener { response ->
                    val statusCode = response["statusCode"]
                    if (statusCode != "OK") {
                        updateUI {
                            show("Oops!")
                            tv_message.text = response["errorMessage"].toString()
                        }
                        Log.i("backend response", "${response["statusCode"]}, ${response["errorCode"]}")
                    } else {
                        updateUI {
                            show("Yay!")
                            tv_message.text = getString(R.string.waiting_over)
                        }
                        emjojifiedUrl = response["emojifiedUrl"].toString()
                        downloadAndShowImage()
                    }
                    deleteSourceImage()
                },
                Response.ErrorListener { err ->
                    updateUI {
                        show("Error calling backend!")
                        tv_message.text = getString(R.string.backend_error)
                    }
                    Log.e("backend", err.message)
                    deleteSourceImage()
                })
        request.retryPolicy = DefaultRetryPolicy(50000, 5, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
        queue.add(request)
    }

    private fun deleteSourceImage() = storageRef.child(imageId).delete()
            .addOnSuccessListener { Log.i("deleted", "Source image successfully deleted!") }
            .addOnFailureListener { err -> Log.e("delete", err.message) }

    private fun uploadImage(path: String) {
        val file = Uri.fromFile(File(path))
        imageId = System.currentTimeMillis().toString() + ".jpg"
        val imgRef = storageRef.child(imageId)
        updateUI {
            image_view.visibility = View.GONE
            tv_message!!.text = getString(R.string.waiting_msg_1)
        }
        imgRef.putFile(file, StorageMetadata.Builder().setContentType("image/jpg").build())
                .addOnSuccessListener { _ ->
                    updateUI { tv_message.text = getString(R.string.waiting_msg_2) }
                    callEmojifyBackend()
                }
                .addOnFailureListener { err ->
                    updateUI {
                        show("Cloud Storage error!")
                        tv_message.text = getString(R.string.storage_error)
                    }
                    Log.e("storage", err.message)
                }
    }

    private fun downloadAndShowImage() {
        val url = emjojifiedUrl
        updateUI {
            Glide.with(this)
                    .load(url)
                    .apply(RequestOptions().signature(ObjectKey(System.currentTimeMillis())))
                    .apply(RequestOptions()
                            .placeholder(R.drawable.placeholder)
                            .error(R.drawable.placeholder)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .dontTransform()
                            .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                            .skipMemoryCache(true))
                    .into(image_view)
        }
        image_view.visibility = View.VISIBLE
    }

    private fun load(path: String) =
        launch(CommonPool + job) {
            uploadImage(path)
        }

    private fun selectImage() {
        Album.image(this)
            .singleChoice()
            .camera(true)
            .columnCount(2)
            .widget(
                Widget.newDarkBuilder(this)
                    .title(toolbar!!.title.toString())
                    .build()
            )
            .onResult { result ->
                albumFiles = result
                tv_message.visibility = View.VISIBLE
                if (result.size > 0) load(result[0].path)
            }
            .onCancel {
                finish()
            }
            .start()
    }

    private fun previewImage(position: Int) {
        if (albumFiles == null || albumFiles!!.size == 0) Toast.makeText(this, R.string.no_selected, Toast.LENGTH_LONG).show()
        else {
            Album.galleryAlbum(this)
                .checkable(false)
                .checkedList(albumFiles)
                .currentPosition(position)
                .widget(
                    Widget.newDarkBuilder(this)
                        .title(toolbar!!.title.toString())
                        .build()
                )
                .onResult { result ->
                    albumFiles = result
                    adapter!!.notifyDataSetChanged(albumFiles!!)
                    tv_message.visibility = if (result.size > 0) View.VISIBLE else View.GONE
                }
                .start()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_album_image, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when (id) {
            android.R.id.home -> this.onBackPressed()
            R.id.menu_eye -> previewImage(0)
        }
        return true
    }

    override fun onBackPressed() {
        println("CALLED!")
        selectImage()
    }
}