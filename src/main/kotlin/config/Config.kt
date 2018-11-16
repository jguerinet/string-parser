/*
 * Copyright 2013-2018 Julien Guerinet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.guerinet.sp.config

import com.guerinet.sp.Language

/**
 * Parsed Config Json
 * @author Julien Guerinet
 * @since 5.0.0
 *
 * @param platform  Platform we are parsing for (Android, iOS, Web)
 * @param sources   List of sources the Strings are coming from
 * @param languages List of languages we are parsing
 */
class Config(val platform: String, val sources: List<Source>, val languages: List<Language>) {

    /**
     * Moshi Constructor
     */
    constructor() : this("", listOf(), listOf())
}