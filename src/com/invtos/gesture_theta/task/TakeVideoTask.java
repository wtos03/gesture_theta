package com.invtos.gesture_theta.task;


/**
 * Copyright 2018 Ricoh Company, Ltd.
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
 *
 * Copy and modified from TakePicturetask
 *
 */

import android.os.AsyncTask;
import com.invtos.gesture_theta.network.HttpConnector;
import com.invtos.gesture_theta.network.HttpEventListener;

public class TakeVideoTask extends AsyncTask<Void, Void, HttpConnector.ShootResult> {
    private Callback mCallback;

    public TakeVideoTask(Callback callback) {
        this.mCallback = callback;
    }

    @Override
    protected void onPreExecute() {

    }

    @Override
    protected HttpConnector.ShootResult doInBackground(Void... params) {
        CaptureListener postviewListener = new CaptureListener();
        HttpConnector video = new HttpConnector("127.0.0.1:8080");

        return video.startVideo(postviewListener);
    }

    @Override
    protected void onPostExecute(HttpConnector.ShootResult result) {

    }

    private class CaptureListener implements HttpEventListener {
        private String latestCapturedFileId;

        @Override
        public void onCheckStatus(boolean newStatus) {

        }

        @Override
        public void onObjectChanged(String latestCapturedFileId) {
            this.latestCapturedFileId = latestCapturedFileId;
            mCallback.onTakeVideo(latestCapturedFileId);
        }

        @Override
        public void onCompleted() {

        }

        @Override
        public void onError(String errorMessage) {

        }
    }

    public interface Callback {
        void onTakeVideo(String fileUrl);
    }

}
