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

package android.credentials.ui;

import android.annotation.NonNull;
import android.app.slice.Slice;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * A credential, save, or action entry to be rendered.
 *
 * @hide
 */
public class Entry implements Parcelable {
    // TODO: these constants should go to jetpack.
    public static final String VERSION = "v1";
    public static final Uri CREDENTIAL_MANAGER_ENTRY_URI = Uri.parse("credentialmanager.slice");
    // TODO: remove these hint constants and use the credential entry & action ones defined below.
    public static final String HINT_TITLE = "HINT_TITLE";
    public static final String HINT_SUBTITLE = "HINT_SUBTITLE";
    public static final String HINT_ICON = "HINT_ICON";
    /**
     * 1. CREDENTIAL ENTRY CONSTANTS
     */
    // User profile picture associated with this credential entry.
    public static final String HINT_PROFILE_ICON = "HINT_PROFILE_ICON";
    public static final String HINT_CREDENTIAL_TYPE_ICON = "HINT_CREDENTIAL_TYPE_ICON";
     // The user account name of this provider app associated with this entry.
     // Note: this is independent from the request app.
    public static final String HINT_USER_PROVIDER_ACCOUNT_NAME = "HINT_USER_PROVIDER_ACCOUNT_NAME";
    public static final String HINT_PASSWORD_COUNT = "HINT_PASSWORD_COUNT";
    public static final String HINT_PASSKEY_COUNT = "HINT_PASSKEY_COUNT";
    public static final String HINT_TOTAL_CREDENTIAL_COUNT = "HINT_TOTAL_CREDENTIAL_COUNT";
    public static final String HINT_LAST_USED_TIME_MILLIS = "HINT_LAST_USED_TIME_MILLIS";
    /** Below are only available for get flows. */
    public static final String HINT_NOTE = "HINT_NOTE";
    public static final String HINT_USER_NAME = "HINT_USER_NAME";
    public static final String HINT_CREDENTIAL_TYPE = "HINT_CREDENTIAL_TYPE";
    public static final String HINT_PASSKEY_USER_DISPLAY_NAME = "HINT_PASSKEY_USER_DISPLAY_NAME";
    public static final String HINT_PASSWORD_VALUE = "HINT_PASSWORD_VALUE";

    /**
     * 2. ACTION CONSTANTS
     */
    public static final String HINT_ACTION_TITLE = "HINT_ACTION_TITLE";
    public static final String HINT_ACTION_SUBTEXT = "HINT_ACTION_SUBTEXT";
    public static final String HINT_ACTION_ICON = "HINT_ACTION_ICON";

    /**
    * The intent extra key for the action chip {@code Entry} list when launching the UX activities.
    */
    public static final String EXTRA_ENTRY_LIST_ACTION_CHIP =
            "android.credentials.ui.extra.ENTRY_LIST_ACTION_CHIP";
    /**
    * The intent extra key for the credential / save {@code Entry} list when launching the UX
    * activities.
    */
    public static final String EXTRA_ENTRY_LIST_CREDENTIAL =
            "android.credentials.ui.extra.ENTRY_LIST_CREDENTIAL";
    /**
    * The intent extra key for the authentication action {@code Entry} when launching the UX
    * activities.
    */
    public static final String EXTRA_ENTRY_AUTHENTICATION_ACTION =
            "android.credentials.ui.extra.ENTRY_AUTHENTICATION_ACTION";

    // TODO: change to string key + string subkey.
    private final int mId;

    @NonNull
    private final Slice mSlice;

    protected Entry(@NonNull Parcel in) {
        int entryId = in.readInt();
        Slice slice = Slice.CREATOR.createFromParcel(in);

        mId = entryId;
        mSlice = slice;
        AnnotationValidations.validate(NonNull.class, null, mSlice);
    }

    public Entry(int id, @NonNull Slice slice) {
        mId = id;
        mSlice = slice;
    }

    /**
    * Returns the id of this entry that's unique within the context of the CredentialManager
    * request.
    */
    public int getEntryId() {
        return mId;
    }

    /**
    * Returns the Slice to be rendered.
    */
    @NonNull
    public Slice getSlice() {
        return mSlice;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        mSlice.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<Entry> CREATOR = new Creator<Entry>() {
        @Override
        public Entry createFromParcel(@NonNull Parcel in) {
            return new Entry(in);
        }

        @Override
        public Entry[] newArray(int size) {
            return new Entry[size];
        }
    };
}
