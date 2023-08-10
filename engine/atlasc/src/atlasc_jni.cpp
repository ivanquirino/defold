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

#include "atlasc.h"
#include "Atlasc_jni.h"
#include "jni/jni_util.h"

#include <jni.h>

#include <dlib/array.h>
#include <dlib/log.h>
#include <dlib/dstrings.h>

#define STB_IMAGE_IMPLEMENTATION
#include <stb/stb_image.h>


static void OnAtlasError(void* ctx, dmAtlasc::Result result, const char* error_string)
{
    printf("ATLASC: %d: %s\n", result, error_string);
}


JNIEXPORT jobject JNICALL Java_JniTest_GetDefaultOptions(JNIEnv* env, jclass cls)
{
    dmLogDebug("Java_JniTest_GetDefaultOptions\n");
    dmJNI::SignalContextScope env_scope(env);
    dmAtlasc::jni::ScopedContext jni_scope(env);

    jobject jni_options = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();
        dmAtlasc::Options options;
        jni_options = dmAtlasc::jni::C2J_CreateOptions(env, &jni_scope.m_TypeInfos, &options);
    DM_JNI_GUARD_SCOPE_END(return 0;);
    return jni_options;
}

static jobject DoCreateAtlas(JNIEnv* env, dmAtlasc::jni::TypeInfos* type_infos, jobject _options, jobject _images)
{
    uint32_t images_count = 0;
    dmAtlasc::SourceImage* images = dmAtlasc::jni::J2C_CreateSourceImageArray(env, type_infos, (jobjectArray)_images, &images_count);
    if (!images || !images_count)
    {
        dmLogError("Source image array was empty or invalid");
        return 0;
    }

    dmAtlasc::Options options;
    dmAtlasc::jni::J2C_CreateOptions(env, type_infos, _options, &options);

    dmAtlasc::Atlas* atlas = dmAtlasc::CreateAtlas(options, images, images_count, OnAtlasError, 0);
    jobject jatlas = dmAtlasc::jni::C2J_CreateAtlas(env, type_infos, atlas);
    dmAtlasc::DestroyAtlas(atlas);
    return jatlas;
}

JNIEXPORT jobject JNICALL Java_JniTest_CreateAtlas(JNIEnv* env, jclass cls, jobject _options, jobject _images)
{
    dmLogDebug("Java_JniTest_CreateAtlas\n");
    dmJNI::SignalContextScope env_scope(env);
    dmAtlasc::jni::ScopedContext jni_scope(env);

    if (!_options)
    {
        dmLogError("Options argument is null");
        return 0;
    }

    if (!_images)
    {
        dmLogError("Images argument is null");
        return 0;
    }

    jobject jatlas = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();
        jatlas = DoCreateAtlas(env, &jni_scope.m_TypeInfos, _options, _images);
    DM_JNI_GUARD_SCOPE_END(return 0;);
    return jatlas;
}

static void DestroyImage(dmAtlasc::SourceImage* image)
{
    free((void*)image->m_Path);
    free((void*)image->m_Data);
}

static int LoadImage(const char* path, dmAtlasc::SourceImage* image)
{
    memset(image, 0, sizeof(dmAtlasc::SourceImage));
    image->m_Data = stbi_load(path, &image->m_Size.width, &image->m_Size.height, &image->m_NumChannels, 0);
    if (!image->m_Data)
    {
        DestroyImage(image);
        printf("Failed to load %s\n", path);
        return 0;
    }
    image->m_Path = strdup(path);
    image->m_DataCount = image->m_Size.width * image->m_Size.height * image->m_NumChannels;
    return 1;
}

static jobject DoLoadImage(JNIEnv* env, dmAtlasc::jni::TypeInfos* type_infos, jstring _path)
{
    dmJNI::ScopedString path(env, _path);

    dmAtlasc::SourceImage image;
    if (!LoadImage(path.m_String, &image))
    {
        dmLogError("Failed to load image '%s'", path.m_String);
        return 0;
    }

    jobject jni_image = dmAtlasc::jni::C2J_CreateSourceImage(env, type_infos, &image);
    DestroyImage(&image);
    return jni_image;
}

JNIEXPORT jobject JNICALL Java_JniTest_LoadImage(JNIEnv* env, jclass cls, jstring _path)
{
    dmLogDebug("Java_JniTest_LoadImage\n");
    dmJNI::SignalContextScope env_scope(env);
    dmAtlasc::jni::ScopedContext jni_scope(env);

    if (!_path)
    {
        dmLogError("No path specified");
        return 0;
    }

    jobject image = 0;
    DM_JNI_GUARD_SCOPE_BEGIN();
        image = DoLoadImage(env, &jni_scope.m_TypeInfos, _path);
    DM_JNI_GUARD_SCOPE_END(return 0;);
    return image;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {

    dmLogDebug("JNI_OnLoad ->\n");
    dmJNI::EnableDefaultSignalHandlers(vm);

    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
        printf("JNI_OnLoad GetEnv error\n");
        return JNI_ERR;
    }

    // Find your class. JNI_OnLoad is called from the correct class loader context for this to work.
    jclass c = env->FindClass("com/dynamo/bob/pipeline/AtlasCompiler");
    dmLogDebug("JNI_OnLoad: c = %p\n", c);
    if (c == 0)
      return JNI_ERR;

    // Register your class' native methods.
    // Don't forget to add them to the corresponding java file (e.g. AtlasCompiler.java)
    static const JNINativeMethod methods[] = {
        {(char*)"GetDefaultOptions", (char*)"()L" CLASS_NAME "$Options;", reinterpret_cast<void*>(Java_JniTest_GetDefaultOptions)},
        {(char*)"CreateAtlas", (char*)"(L" CLASS_NAME "$Options;[L" CLASS_NAME "$SourceImage;)L" CLASS_NAME "$Atlas;", reinterpret_cast<void*>(Java_JniTest_CreateAtlas)},
        {(char*)"LoadImage", (char*)"(Ljava/lang/String;)L" CLASS_NAME "$SourceImage;", reinterpret_cast<void*>(Java_JniTest_LoadImage)},
    };
    int rc = env->RegisterNatives(c, methods, sizeof(methods)/sizeof(JNINativeMethod));
    env->DeleteLocalRef(c);

    if (rc != JNI_OK) return rc;

    dmLogDebug("JNI_OnLoad return.\n");
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved)
{
    dmLogDebug("JNI_OnUnload ->\n");
}
