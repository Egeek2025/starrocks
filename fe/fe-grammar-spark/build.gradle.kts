// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Spark SQL grammar vendored from Apache Spark 3.5. It lives in its own module because
// fe-grammar applies a single global "-package" argument to all of its grammars.
plugins {
    antlr
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

configurations.configureEach {
    resolutionStrategy.force("org.antlr:antlr4-runtime:${project.ext["antlr.version"]}")
}
dependencies {
    antlr("org.antlr:antlr4:${project.ext["antlr.version"]}")

    implementation("org.antlr:antlr4-runtime")
}

val sparkGrammarPackage = "com.starrocks.sql.parser.spark.gen"

// Configure ANTLR plugin. SparkSqlBaseParser uses "options { tokenVocab = SparkSqlBaseLexer; }",
// so the lexer's .tokens file must be visible through -lib. The Gradle ANTLR plugin writes the
// generated files below <outputDirectory>/<package path> when "-package" is given.
tasks.generateGrammarSource {
    maxHeapSize = "1g"

    val grammarDir = file("src/main/antlr/com/starrocks/sql/parser/spark/gen")
    val tokensDir = layout.buildDirectory.dir(
        "generated-src/antlr/main/" + sparkGrammarPackage.replace('.', '/')
    ).get().asFile

    arguments = listOf(
        "-visitor",
        "-package", sparkGrammarPackage,
        "-lib", tokensDir.absolutePath
    )
    // -lib must exist before ANTLR starts
    doFirst {
        tokensDir.mkdirs()
    }
    // keep the lexer first so that SparkSqlBaseLexer.tokens exists when the parser is generated
    source = fileTree(grammarDir) { include("SparkSqlBaseLexer.g4") } +
        fileTree(grammarDir) { include("SparkSqlBaseParser.g4") }
}

// Add source generation tasks to the build process
tasks.compileJava {
    dependsOn("generateGrammarSource")
}
