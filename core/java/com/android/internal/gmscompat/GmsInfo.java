/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.gmscompat;

/** @hide */
public final class GmsInfo {
    // Package names for GMS apps
    public static final String PACKAGE_GSF = "com.google.android.gsf"; // "Google Services Framework"
    public static final String PACKAGE_GMS_CORE = "com.google.android.gms"; // "Play services"
    public static final String PACKAGE_PLAY_STORE = "com.android.vending";

    // "Google" app. "GSA" (G Search App) is its internal name
    public static final String PACKAGE_GSA = "com.google.android.googlequicksearchbox";

    // Used for restricting accessibility of exported components, reducing the scope of broadcasts, etc.
    // Held by GSF, GmsCore, Play Store.
    public static final String SIGNATURE_PROTECTED_PERMISSION = "com.google.android.providers.gsf.permission.WRITE_GSERVICES";

    // Shared user ID for GMS Core and GSF
    public static final String SHARED_USER_ID = "com.google.uid.shared";

    public static final String[] VALID_SIGNATURES = {
        // "bd32" SHA256withRSA issued in March 2020, used by GSF, GMS Core, Play Store
        "308205c8308203b0a0030201020214108a650873f92f8e51ed42a432372d6a4519eb69300d06092a864886f70d01010b05003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643020170d3230303330393139353730315a180f32303530303330393139353730315a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820222300d06092a864886f70d01010105000382020f003082020a0282020100b3259b53aadaf281937c9452d3b65a24c4824b602bc4b930b4df9b1d39c72f23328c57cb81166108d82b71369596a0e4b39fc461f6256a2558773ebd5c323df07b93d92308de8b4160347a6a14e1e0a9abdb59977ee0a3902816e068a3733dd40c66a007d07b0c72dfab0a314379b1ece4b2920b7ccb46344a91d169c90e511ebad39f0ff15045cc8ff68b0caeb96cec6d54494a196de2dc1eca7a70549252c08ca7a61dc0cdbf17756c1f52157b092428c44d9cf13e5289cecba68aa17e5eac8f4de5b4ecf1af6a0723a140bc17874268fb328ca4261a21f2689ebe25afb137822fe0194b1b67a365883e2a85e44fcb53ea7b8cb1a73e804149833ccaebf8bad56633a80aa3826b999c5cd7d9c4149b7753c5c3be99c94b202b4990e497e1d94d93d93d32fce9a4ba3f7f78c81e3f59c8fb6b13a4c6a96371c5bc4db724799a116591752e555e6da3ea22f76a7f1ff556f650a7cf7e67e3cd67442d5be22ecaf2547bf13c425fcab95c71e29c6278151be89d331d0cc645d152d48fd2e7e84d51808761bcbd764c821b6a4b5af7b923e7160e9ef3b84a7fbb7f45c207a9b09d10ae06a1650260b795ccd00679ffe5cf1e377e4f8b8afddee58db56b6599a29f624835a7377846e1636fb3aace1aaf2cda32e144f465d8b9b2bac0cb6544193eee49b1565d20168837d922bbeb8184e44be9c4332086f91cf35509f06c1a529d0203010001a350304e300c0603551d13040530030101ff301d0603551d0e0416041496d012a9b243b36e031231a4c65f7bbaf803713b301f0603551d2304183016801496d012a9b243b36e031231a4c65f7bbaf803713b300d06092a864886f70d01010b05000382020100ab9b81b1a7280b45b9118027a0817ec9eb27c35a84232359e16ad4c166ddf81e02f46dc9f542db22e015d3a58334d9e3cc95c23206bd19a5766b3b89587614bfd852ccaf97b329db8f0e3140f45ba97d3640a97506aee47be35b26cf39e719f58a6dd75e0ef9aa7b846e034edc678873e4258310a283376b21b4defd21ed00854d4cd458233d384f9fd2cdf546a33c184ee773b960e9490cc86b55f461541b5ecefb438bac594e31d438eca86a6276c37bda687be1df28247ed0adfcb42103c234cfda40889961d12aa39ddf76de799cd68e79a8c23f217bea39467dff131c9ac610e5815440612fb6373b693f7f87bda3abf060cacd0023cb82ed68a9e038c8a10e87af4e3659908546a08e8d18b1b9c2487e9cda2c36b528331f6c6202b862fea59982147df139b758f9f3eb0c46cbcc97f8a7f8c8f6e25697edd1ad7752e5a1b13383acd8555ad161f6b1c719176a890dd09bf39a6d1d8183433ae91bd91d046df35da7d7f008b74f21a488434565a7f964f900b2e87fa9a85854010a4f123a4504a1a0021d395e7f2be1905c9276391c75166e12f8f4a4ee1597223c99611fe9aaa69d90fdcf0f2419cd1593005f6555d190b290f711f3592b0d30c24c6bf2a9e27c04d874830806b6d2f5c21d1146bd25393456146e758d627cad6ef50d1d67e15e43a52111b77cad77246c13a62fb765b68c5da6e54ea12aa4a3f329f0",
        // "58e1" MD5withRSA issued in April 2008, used by GSA and some versions of GSF, GMS Core, Play Store
        "308204a830820390a003020102020900d585b86c7dd34ef5300d06092a864886f70d0101040500308194310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d0109011613616e64726f696440616e64726f69642e636f6d301e170d3038303431353233333635365a170d3335303930313233333635365a308194310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d0109011613616e64726f696440616e64726f69642e636f6d30820120300d06092a864886f70d01010105000382010d00308201080282010100d6ce2e080abfe2314dd18db3cfd3185cb43d33fa0c74e1bdb6d1db8913f62c5c39df56f846813d65bec0f3ca426b07c5a8ed5a3990c167e76bc999b927894b8f0b22001994a92915e572c56d2a301ba36fc5fc113ad6cb9e7435a16d23ab7dfaeee165e4df1f0a8dbda70a869d516c4e9d051196ca7c0c557f175bc375f948c56aae86089ba44f8aa6a4dd9a7dbf2c0a352282ad06b8cc185eb15579eef86d080b1d6189c0f9af98b1c2ebd107ea45abdb68a3c7838a5e5488c76c53d40b121de7bbd30e620c188ae1aa61dbbc87dd3c645f2f55f3d4c375ec4070a93f7151d83670c16a971abe5ef2d11890e1b8aef3298cf066bf9e6ce144ac9ae86d1c1b0f020103a381fc3081f9301d0603551d0e041604148d1cc5be954c433c61863a15b04cbc03f24fe0b23081c90603551d230481c13081be80148d1cc5be954c433c61863a15b04cbc03f24fe0b2a1819aa48197308194310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d0109011613616e64726f696440616e64726f69642e636f6d820900d585b86c7dd34ef5300c0603551d13040530030101ff300d06092a864886f70d0101040500038201010019d30cf105fb78923f4c0d7dd223233d40967acfce00081d5bd7c6e9d6ed206b0e11209506416ca244939913d26b4aa0e0f524cad2bb5c6e4ca1016a15916ea1ec5dc95a5e3a010036f49248d5109bbf2e1e618186673a3be56daf0b77b1c229e3c255e3e84c905d2387efba09cbf13b202b4e5a22c93263484a23d2fc29fa9f1939759733afd8aa160f4296c2d0163e8182859c6643e9c1962fa0c18333335bc090ff9a6b22ded1ad444229a539a94eefadabd065ced24b3e51e5dd7b66787bef12fe97fba484c423fb4ff8cc494c02f0f5051612ff6529393e8e46eac5bb21f277c151aa5f2aa627d1e89da70ab6033569de3b9897bfff7ca9da3e1243f60b",
        // "38d1" MD5withRSA issued in August 2008, used by GSA and some versions of GSF, GMS Core, Play Store
        "308204433082032ba003020102020900c2e08746644a308d300d06092a864886f70d01010405003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964301e170d3038303832313233313333345a170d3336303130373233313333345a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820120300d06092a864886f70d01010105000382010d00308201080282010100ab562e00d83ba208ae0a966f124e29da11f2ab56d08f58e2cca91303e9b754d372f640a71b1dcb130967624e4656a7776a92193db2e5bfb724a91e77188b0e6a47a43b33d9609b77183145ccdf7b2e586674c9e1565b1f4c6a5955bff251a63dabf9c55c27222252e875e4f8154a645f897168c0b1bfc612eabf785769bb34aa7984dc7e2ea2764cae8307d8c17154d7ee5f64a51a44a602c249054157dc02cd5f5c0e55fbef8519fbe327f0b1511692c5a06f19d18385f5c4dbc2d6b93f68cc2979c70e18ab93866b3bd5db8999552a0e3b4c99df58fb918bedc182ba35e003c1b4b10dd244a8ee24fffd333872ab5221985edab0fc0d0b145b6aa192858e79020103a381d93081d6301d0603551d0e04160414c77d8cc2211756259a7fd382df6be398e4d786a53081a60603551d2304819e30819b8014c77d8cc2211756259a7fd382df6be398e4d786a5a178a4763074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964820900c2e08746644a308d300c0603551d13040530030101ff300d06092a864886f70d010104050003820101006dd252ceef85302c360aaace939bcff2cca904bb5d7a1661f8ae46b2994204d0ff4a68c7ed1a531ec4595a623ce60763b167297a7ae35712c407f208f0cb109429124d7b106219c084ca3eb3f9ad5fb871ef92269a8be28bf16d44c8d9a08e6cb2f005bb3fe2cb96447e868e731076ad45b33f6009ea19c161e62641aa99271dfd5228c5c587875ddb7f452758d661f6cc0cccb7352e424cc4365c523532f7325137593c4ae341f4db41edda0d0b1071a7c440f0fe9ea01cb627ca674369d084bd2fd911ff06cdbf2cfa10dc0f893ae35762919048c7efc64c7144178342f70581c9de573af55b390dd7fdb9418631895d5f759f30112687ff621410c069308a"
    };

    // these packages are included in the system image, certificate checks aren't needed
    public static final String[] EUICC_PACKAGES = {
            "com.google.euiccpixel",
            "com.google.android.euicc",
    };

    public static final String[] DEPENDENCIES_OF_EUICC_PACKAGES = { PACKAGE_GSF, PACKAGE_GMS_CORE, PACKAGE_PLAY_STORE };

    private GmsInfo() { }
}
