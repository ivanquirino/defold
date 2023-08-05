// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include "testapi.h"
#include "Testapi_jni.h"
#include "jni_util.h"

#include <jni.h>

#include <dlib/array.h>
#include <dlib/log.h>
#include <dlib/dstrings.h>

#define CLASS_NAME_JNI_TEST "com/dynamo/bob/pipeline/JniTest"

JNIEXPORT jobject JNICALL Java_JniTest_TestCreateVec2i(JNIEnv* env, jclass cls)
{
    dmLogInfo("Java_JniTest_TestCreateVec2i: env = %p\n", env);
    dmJNI::SignalContextScope env_scope(env);
    dmJniTest::ScopedContext jni_scope(env);

    jobject jvec = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();
        dmJniTest::Vec2i vec;
        vec.x = 1;
        vec.y = 2;
        jvec = dmJniTest::CreateVec2i(env, &jni_scope.m_TypeInfos, &vec);
    DM_JNI_GUARD_SCOPE_END(return 0;);

    return jvec;
}

JNIEXPORT jobject JNICALL Java_JniTest_TestCreateRecti(JNIEnv* env, jclass cls)
{
    dmLogInfo("Java_JniTest_TestCreatRect2i: env = %p\n", env);
    dmJNI::SignalContextScope env_scope(env);
    dmJniTest::ScopedContext jni_scope(env);

    jobject jrect = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();
        dmJniTest::Recti rect;
        rect.m_Min.x = -2;
        rect.m_Min.y = -3;
        rect.m_Max.x = 4;
        rect.m_Max.y = 5;
        jrect = dmJniTest::CreateRecti(env, &jni_scope.m_TypeInfos, &rect);
    DM_JNI_GUARD_SCOPE_END(return 0;);
    return jrect;
}

JNIEXPORT jobject JNICALL Java_JniTest_TestCreateMisc(JNIEnv* env, jclass cls)
{
    dmLogInfo("Java_JniTest_TestCreateMisc:\n");
    dmJNI::SignalContextScope env_scope(env);
    dmJniTest::ScopedContext jni_scope(env);

    jobject jmisc = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();
        const char* s = "Hello World!";
        dmJniTest::Misc misc;
        misc.m_TestEnum = dmJniTest::TE_VALUE_B;
        misc.m_String = s;
        jmisc = dmJniTest::CreateMisc(env, &jni_scope.m_TypeInfos, &misc);
    DM_JNI_GUARD_SCOPE_END(return 0;);
    return jmisc;
}

JNIEXPORT jobject JNICALL Java_JniTest_TestDuplicateRecti(JNIEnv* env, jclass cls, jobject jni_rect)
{
    dmLogInfo("Java_JniTest_TestDuplicateRecti: env = %p\n", env);
    dmJNI::SignalContextScope env_scope(env);
    dmJniTest::ScopedContext jni_scope(env);

    jobject jni_out_rect = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();

        dmJniTest::Recti in_rect = {};
        dmJniTest::GetRecti(env, &jni_scope.m_TypeInfos, jni_rect, &in_rect);

        // copy and modify
        dmJniTest::Recti out_rect;
        out_rect.m_Min.x = in_rect.m_Min.x + 1;
        out_rect.m_Min.y = in_rect.m_Min.y + 1;
        out_rect.m_Max.x = in_rect.m_Max.x + 1;
        out_rect.m_Max.y = in_rect.m_Max.y + 1;
        jni_out_rect = dmJniTest::CreateRecti(env, &jni_scope.m_TypeInfos, &out_rect);
    DM_JNI_GUARD_SCOPE_END(return 0;);
    return jni_out_rect;
}

JNIEXPORT jobject JNICALL Java_JniTest_TestCreateArrays(JNIEnv* env, jclass cls)
{
    dmLogInfo("Java_JniTest_TestCreateArrays: env = %p\n", env);
    dmJNI::SignalContextScope env_scope(env);
    dmJniTest::ScopedContext jni_scope(env);

    jobject jdata = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();
        dmJniTest::Arrays arrays;

        const uint8_t data[] = {1,2,4,8};
        arrays.m_Data = data;
        arrays.m_DataCount = DM_ARRAY_SIZE(data);

        const uint8_t data2[] = {2,4,8,16,32};
        arrays.m_Data2.SetCapacity(DM_ARRAY_SIZE(data2));
        for (uint32_t i = 0; i < DM_ARRAY_SIZE(data2); ++i)
            arrays.m_Data2.Push(data2[i]);


        const dmJniTest::Recti rects[] = {
            { {1,2}, {3,4} },
            { {5,6}, {7,8} },
            { {9,10}, {11,12} }
        };

        arrays.m_Rects = rects;
        arrays.m_RectsCount = DM_ARRAY_SIZE(rects);

        arrays.m_Rects2.SetCapacity(DM_ARRAY_SIZE(rects));
        for (uint32_t i = 0; i < DM_ARRAY_SIZE(rects); ++i)
            arrays.m_Rects2.Push(rects[i]);

        jdata = dmJniTest::CreateArrays(env, &jni_scope.m_TypeInfos, &arrays);
    DM_JNI_GUARD_SCOPE_END(return 0;);
    return jdata;
}

// JNIEXPORT void JNICALL Java_JniTest_TestException(JNIEnv* env, jclass cls, jstring j_message)
// {
//     dmJNI::SignalContextScope env_scope(env);
//     ScopedString s_message(env, j_message);
//     const char* message = s_message.m_String;
//     printf("Received message: %s\n", message);
//     dmJNI::TestSignalFromString(message);
// }

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {

    dmLogInfo("JNI_OnLoad ->\n");
    dmJNI::EnableDefaultSignalHandlers(vm);

    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
        printf("JNI_OnLoad GetEnv error\n");
        return JNI_ERR;
    }

    // Find your class. JNI_OnLoad is called from the correct class loader context for this to work.
    jclass c = env->FindClass(CLASS_NAME_JNI_TEST);
    dmLogInfo("JNI_OnLoad: c = %p\n", c);
    if (c == 0)
      return JNI_ERR;

    // Register your class' native methods.
    // Don't forget to add them to the corresponding java file (e.g. JniTest.java)
    static const JNINativeMethod methods[] = {
        {(char*)"TestCreateVec2i", (char*)"()L" CLASS_NAME "$Vec2i;", reinterpret_cast<void*>(Java_JniTest_TestCreateVec2i)},
        {(char*)"TestCreateRecti", (char*)"()L" CLASS_NAME "$Recti;", reinterpret_cast<void*>(Java_JniTest_TestCreateRecti)},
        {(char*)"TestCreateArrays", (char*)"()L" CLASS_NAME "$Arrays;", reinterpret_cast<void*>(Java_JniTest_TestCreateArrays)},
        {(char*)"TestCreateMisc", (char*)"()L" CLASS_NAME "$Misc;", reinterpret_cast<void*>(Java_JniTest_TestCreateMisc)},

        {(char*)"TestDuplicateRecti", (char*)"(L" CLASS_NAME "$Recti;)L" CLASS_NAME "$Recti;", reinterpret_cast<void*>(Java_JniTest_TestDuplicateRecti)},

        //{"TestException", "(Ljava/lang/String;)V", reinterpret_cast<void*>(Java_JniTest_TestException)},
    };
    int rc = env->RegisterNatives(c, methods, sizeof(methods)/sizeof(JNINativeMethod));
    env->DeleteLocalRef(c);

    if (rc != JNI_OK) return rc;

    dmLogInfo("JNI_OnLoad return.\n");
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved)
{
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
        printf("JNI_OnUnload: GetEnv error\n");
        return;
    }

    jclass c = env->FindClass(CLASS_NAME_JNI_TEST);
    dmLogInfo("JNI_OnUnload: c = %p\n", c);
    if (c == 0)
      return;

    env->UnregisterNatives(c);
    env->DeleteLocalRef(c);
}
