/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.tools.discover.views;

/**
 * How much of what a declaration uses an answer prints, and whether it prints the documentation.
 *
 * <p>By default an answer prints the declaration asked for, and the declarations that declaration includes with
 * {@code *T}, and lists every other type it names. {@code --expand-types} also prints the types it uses, one level
 * deep; {@code --no-docs} leaves the {@code #} documentation out of every declaration printed. The two are
 * independent, and change what is printed and nothing else.
 *
 * @param expandTypes whether the types a declaration uses are printed
 * @param noDocs whether documentation is left out of every declaration printed
 * @since 0.1.0
 */
public record Shape(boolean expandTypes, boolean noDocs) {

    /** Neither flag: the declaration asked for, its inclusions, and the other types it uses listed by name. */
    public static final Shape DEFAULT = new Shape(false, false);

    /** Whether either flag was given, which only an answer that prints a declaration can honour. */
    public boolean any() {
        return expandTypes || noDocs;
    }
}
