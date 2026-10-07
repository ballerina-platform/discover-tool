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

package io.ballerina.tools.discover;

import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Patches;
import io.ballerina.tools.discover.model.RecordField;
import io.ballerina.tools.discover.model.Service;
import io.ballerina.tools.discover.model.TypeDef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-package corrections, pinned against the fixtures they correct.
 *
 * <p>A patch is a hand-maintained claim about a package, which is exactly the kind of thing that rots silently:
 * Central starts publishing the field, or the package renames the type, and the correction goes on overriding
 * reality. So each one is pinned in BOTH directions where it has a negative case — what it must change, and what
 * it must leave alone.
 *
 * @since 0.1.0
 */
public class PatchesTest {

    private static Map<String, TypeDef.ErrorDef> errorsOf(String slug) {
        Map<String, TypeDef.ErrorDef> errors = new LinkedHashMap<>();
        for (TypeDef typeDef : FixtureCorpus.libraryFor(slug).typeDefs()) {
            if (typeDef instanceof TypeDef.ErrorDef error) {
                errors.put(error.name(), error);
            }
        }
        return errors;
    }

    private static String baseOf(TypeDef.ErrorDef error) {
        return error.base().map(base -> base.name()).orElse(null);
    }

    // Errors and aliases both: Central files some detail-argument sites under intersectionTypes, not errors.
    private static Map<String, String> descriptorsOf(String slug) {
        Map<String, String> descriptors = new LinkedHashMap<>();
        for (TypeDef typeDef : FixtureCorpus.libraryFor(slug).typeDefs()) {
            if (typeDef instanceof TypeDef.ErrorDef error) {
                descriptors.put(error.name(), baseOf(error));
            } else if (typeDef instanceof TypeDef.Alias alias) {
                descriptors.put(alias.name(), alias.type().name());
            }
        }
        return descriptors;
    }

    /**
     * The whole detail-argument table, every row, in the direction that catches a rot.
     *
     * <p>The correction matches a RENDERED intersection, so a change to how {@code &} is spaced — or to which IR
     * case a category becomes — stops it matching, and a patch that matches nothing produces no failure of its
     * own. Sampling one row would not catch that.
     */
    @Test
    public void everyDeclarationInTheDetailArgumentTableGetsItsArgument() {
        Map<String, Map<String, String>> expected = Map.of(
                "ballerina__http", Map.of(
                        "ApplicationResponseError", "(ClientError & error<Detail>)",
                        "ClientRequestError", "(ApplicationResponseError & error<Detail>)",
                        "RemoteServerError", "(ApplicationResponseError & error<Detail>)",
                        "LoadBalanceActionError", "distinct ResiliencyError & error<LoadBalanceActionErrorData>",
                        "StatusCodeResponseBindingError",
                        "distinct ClientError & error<StatusCodeBindingErrorDetail>"),
                "ballerinax__kafka", Map.of(
                        "PayloadBindingError", "(Error & error<PartitionOffset>)",
                        "PayloadValidationError", "(PayloadBindingError & error<PartitionOffset>)"),
                "ballerina__graphql", Map.of(
                        "HttpError", "(RequestError & error<record {| anydata body; |}>)",
                        "InvalidDocumentError", "(RequestError & error<record {| ErrorDetail[]? errors; |}>)",
                        "PayloadBindingError", "(ClientError & error<record {| ErrorDetail[]? errors; |}>)",
                        "ServerError", "(ClientError & error<record {| json? data?; ErrorDetail[] errors; "
                                + "map<json>? extensions?; |}>)"));

        int rows = 0;
        for (Map.Entry<String, Map<String, String>> library : expected.entrySet()) {
            Map<String, String> descriptors = descriptorsOf(library.getKey());
            for (Map.Entry<String, String> row : library.getValue().entrySet()) {
                Assert.assertEquals(descriptors.get(row.getKey()), row.getValue(),
                        library.getKey() + " " + row.getKey() + " lost its detail argument");
                rows++;
            }
        }
        // Asserted so that deleting a row from the table cannot pass as "every row still lands".
        Assert.assertEquals(rows, 11);
    }

    @Test
    public void anIntersectionOfTwoNamedErrorsIsLeftAlone() {
        // The over-reach guard: these are unparenthesised intersection aliases like `LoadBalanceActionError`,
        // but their members are two named errors rather than a bare `error`, so their sources give them no
        // argument to restore. The declaration-name gate is what excludes them.
        Map<String, String> descriptors = descriptorsOf("ballerina__http");
        Assert.assertEquals(descriptors.get("StatusCodeBindingClientRequestError"),
                "distinct StatusCodeResponseBindingError & ClientRequestError");
        Assert.assertEquals(descriptors.get("StatusCodeBindingRemoteServerError"),
                "distinct StatusCodeResponseBindingError & RemoteServerError");
    }

    @Test
    public void anErrorWhoseBaseIsAPlainReferenceIsLeftAlone() {
        // The negative half of the pin. Attaching `Detail` at the root `Error` is the intuitive reading of
        // Central's `detailType`, and it would make most of http's errors advertise an `int statusCode` they do
        // not have.
        Map<String, TypeDef.ErrorDef> errors = errorsOf("ballerina__http");
        for (String name : new String[] {"SslError", "ListenerError", "ClientError", "AllRetryAttemptsFailed"}) {
            TypeDef.ErrorDef error = errors.get(name);
            Assert.assertNotNull(error, name + " should be published as an error");
            String base = error.base().map(ref -> ref.name()).orElse("error");
            Assert.assertFalse(base.contains("Detail"), name + " must not mention Detail");
        }
        Assert.assertTrue(errors.get("Error").base().isEmpty(), "the root error narrows nothing");
    }

    @Test
    public void clientRequestErrorsChainReachesDetailInTheSamePackage() {
        // Following the chain rather than asserting one line: it is only useful if every hop resolves.
        Map<String, TypeDef.ErrorDef> errors = errorsOf("ballerina__http");
        Assert.assertEquals(baseOf(errors.get("ClientRequestError")),
                "(ApplicationResponseError & error<Detail>)");
        Assert.assertEquals(baseOf(errors.get("ApplicationResponseError")), "(ClientError & error<Detail>)");
        Assert.assertEquals(baseOf(errors.get("ClientError")), "Error");
        Assert.assertTrue(errors.get("Error").isDistinct());

        TypeDef.Rec detail = FixtureCorpus.libraryFor("ballerina__http").typeDefs().stream()
                .filter(TypeDef.Rec.class::isInstance)
                .map(TypeDef.Rec.class::cast)
                .filter(record -> record.name().equals("Detail"))
                .findFirst()
                .orElse(null);
        Assert.assertNotNull(detail);
        Assert.assertEquals(detail.fields().stream().map(RecordField::name).toList(),
                List.of("statusCode", "headers", "body"));
    }

    @Test
    public void everyErrorCarriesTheDistinctnessItDeclared() {
        // Including the one that declares none.
        Map<String, TypeDef.ErrorDef> errors = errorsOf("ballerina__http");
        Assert.assertEquals(errors.size(), 56);
        List<TypeDef.ErrorDef> plain = errors.values().stream()
                .filter(error -> !error.isDistinct())
                .toList();
        // 55 of http's 56 are distinct, which is what keeps the non-distinct half of the rendering table from
        // being dead code nobody has ever exercised.
        Assert.assertEquals(plain.stream().map(TypeDef.ErrorDef::name).toList(),
                List.of("StatusCodeResponseDataBindingError"));
        // And it is not distinct for a reason worth keeping visible: it is an alias for a union of three other
        // errors, which is exactly the shape `distinct` cannot apply to.
        Assert.assertEquals(baseOf(plain.get(0)),
                "MediaTypeBindingStatusCodeClientError|PayloadBindingStatusCodeClientError"
                        + "|HeaderBindingStatusCodeClientError");
    }

    @Test
    public void sapDeclaresClientErrorExactlyOnceAndAsTheReExportItIs() {
        // `simpleNameReferenceTypes` carries `http:ClientError`, so the declaration is the real re-export rather
        // than an injected bare `error`. Exactly one: a duplicate is an ambiguity a name-addressed lookup cannot
        // arbitrate.
        List<TypeDef> named = FixtureCorpus.libraryFor("ballerinax__sap").typeDefs().stream()
                .filter(typeDef -> typeDef.name().equals("ClientError"))
                .toList();
        Assert.assertEquals(named.size(), 1);
        Assert.assertTrue(named.get(0) instanceof TypeDef.Alias, "the re-export, not the injection");
        Assert.assertEquals(((TypeDef.Alias) named.get(0)).type().name(), "http:ClientError");
    }

    @Test
    public void sapDeclaresTheFourNamesItPublishesAndNoPhantom() {
        // No `RequestMessage`: sap's own `.bal` files declare no such type, and every use site is
        // `ballerina/http`-qualified already.
        List<String> declared = FixtureCorpus.libraryFor("ballerinax__sap").declarations().stream()
                .map(TypeDef::name)
                .sorted()
                .toList();
        Assert.assertEquals(declared, List.of("CSRFTokenFetchFailure", "ClientError", "TargetType"));
        // And the name still reaches the caller, at every site that mentions it, qualified to the package
        // that owns it.
        String document = FixtureCorpus.readSnapshot("ballerinax__sap");
        Assert.assertFalse(document.contains("// Unknown type:"));
        Assert.assertEquals(document.split("http:RequestMessage", -1).length - 1, 8);
    }

    /**
     * The declaration Central omits is injected; the references to it are left as the source writes them.
     *
     * <p>Pinned in both directions: rewriting the references to the literal {@code true} instead would diverge
     * from slack's own source and deny the existence of a public declaration.
     */
    @Test
    public void slackDeclaresOkTrueDefOnceAndEveryReferenceResolvesToIt() {
        String document = FixtureCorpus.readSnapshot("ballerinax__slack");
        Assert.assertEquals(document.split("(?m)^public type OkTrueDef true;$", -1).length - 1, 1,
                "exactly one declaration: a duplicate is an ambiguity a name-addressed lookup cannot arbitrate");
        // Every reference, at the spelling `types.bal` uses. The count is asserted so a reader change that
        // silently dropped the field type could not pass.
        Assert.assertEquals(document.split("(?m)^    OkTrueDef ok;$", -1).length - 1, 179);
        Assert.assertFalse(document.contains("    true ok;"), "the source writes the alias, not the literal");
        // And the point of injecting rather than erasing: the name is addressable.
        TypeDef declared = FixtureCorpus.libraryFor("ballerinax__slack").typeDefs().stream()
                .filter(typeDef -> "OkTrueDef".equals(typeDef.name()))
                .findFirst()
                .orElse(null);
        Assert.assertNotNull(declared, "`type ballerinax/slack OkTrueDef` must resolve");
        Assert.assertEquals(((TypeDef.Alias) declared).type().name(), "true");
        // No description, because `types.bal:1146` carries no doc comment.
        Assert.assertEquals(declared.description(), "");
    }

    /**
     * Central does NOT type sheets' 2D values one dimension short: the payload carries
     * {@code arrayDimensions: 2} and {@code isParenthesisedType} on the element, so reading it is enough for
     * all three 2D value fields — {@code Range.values}, {@code ValuesRange.values} and {@code appendValues}'
     * parameter.
     *
     * <p>Keyed on the field's NAME rather than its index: Central's field ORDER is not the fact under test.
     */
    @Test
    public void sheetsReadsTheSecondArrayDimensionAtAllThreeSites() {
        TypeDef.Rec range = FixtureCorpus.libraryFor("ballerinax__googleapis.sheets").typeDefs().stream()
                .filter(TypeDef.Rec.class::isInstance)
                .map(TypeDef.Rec.class::cast)
                .filter(record -> record.name().equals("Range"))
                .findFirst()
                .orElse(null);
        Assert.assertNotNull(range);
        Assert.assertEquals(
                range.fields().stream()
                        .filter(field -> "values".equals(field.name()))
                        .map(field -> field.type().name())
                        .toList(),
                List.of("(int|string|decimal)[][]"));
        // The other two sites.
        String document = FixtureCorpus.readSnapshot("ballerinax__googleapis.sheets");
        Assert.assertTrue(document.contains("(int|string|decimal|boolean|float)[][] values;"));
        Assert.assertTrue(document.contains("(int|string|decimal|boolean|float)[][] values,"));
    }

    /**
     * graphql's {@code ErrorDetail} renders as {@code *parser:ErrorDetail;}, byte-for-byte what
     * {@code records.bal:137-139} declares — so no correction to its spliced-in members is needed.
     */
    @Test
    public void graphqlErrorDetailIsTheInclusionItsSourceDeclares() {
        TypeDef.Rec detail = FixtureCorpus.libraryFor("ballerina__graphql").typeDefs().stream()
                .filter(TypeDef.Rec.class::isInstance)
                .map(TypeDef.Rec.class::cast)
                .filter(record -> record.name().equals("ErrorDetail"))
                .findFirst()
                .orElse(null);
        Assert.assertNotNull(detail);
        // Closed, as `records.bal:137` declares it.
        Assert.assertTrue(detail.isClosed());
        Assert.assertEquals(detail.fields().size(), 1);
        RecordField only = detail.fields().get(0);
        Assert.assertEquals(only.form(), RecordField.Form.INCLUSION);
        Assert.assertEquals(only.type().name(), "parser:ErrorDetail");
    }

    /**
     * The service packages describe their own services: Central publishes every listener and service type,
     * each a real {@code distinct service object} in its package's source, so no generic stand-in replaces them.
     */
    @Test
    public void theServicePackagesDescribeTheirOwnServices() {
        Assert.assertEquals(
                FixtureCorpus.libraryFor("ballerina__http").services().stream()
                        .map(Service::name)
                        .toList(),
                List.of("Service", "ServiceContract", "InterceptableService"));
        Assert.assertEquals(
                FixtureCorpus.libraryFor("ballerina__graphql").services().stream()
                        .map(Service::name)
                        .toList(),
                List.of("Service"));
        // Their contracts are the declarations in the Types section.
        String document = FixtureCorpus.readSnapshot("ballerina__http");
        Assert.assertFalse(document.contains("// --- Service (generic) ---"));
        Assert.assertTrue(document.contains(
                "    function createInterceptors() returns Interceptor|Interceptor[];"));
        Assert.assertTrue(FixtureCorpus.readSnapshot("ballerina__graphql").contains(
                "    isolated remote function execute(Context context, Field 'field) returns anydata|error;"));
    }

    /**
     * The one correction the corpus cannot reach, given a tripwire of its own.
     *
     * <p>There is no {@code ballerinax/client.config} fixture. The compiler rejects
     * {@code import ballerinax/client.config;} with {@code invalid token 'client'} and accepts
     * {@code import ballerinax/'client.config;}: the rejected token is {@code client}, not {@code config}.
     *
     * <p>Asserted through {@code applyPatches} on a hand-built library rather than through a fixture, because
     * the fact under test is about the NAME and needs no payload at all.
     */
    @Test
    public void aReservedWordInAModulePathIsQuotedInTheImportHeader() {
        Library library = new Library("ballerinax/client.config", "", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        Assert.assertEquals(Patches.applyPatches(library).name(), "ballerinax/'client.config");
        // The quoting is the module path's, not every package's: an ordinary name is left alone.
        Assert.assertEquals(
                Patches.applyPatches(library.withName("ballerinax/kafka")).name(), "ballerinax/kafka");
    }

    @Test
    public void applyingThePatchesTwiceChangesNothing() {
        // Two properties in one, and both are worth keeping. For github and postgresql it says the patches are
        // narrow: nothing keys on them, so a second pass is trivially identity. For kafka — which a patch DOES
        // key on — it says the correction is idempotent, because a restored intersection no longer ends in the
        // bare `error` the pattern looks for. A correction that applied twice would render
        // `error<PartitionOffset><PartitionOffset>`.
        for (String slug : new String[] {"ballerinax__github", "ballerinax__kafka", "ballerinax__postgresql"}) {
            Assert.assertEquals(
                    Patches.applyPatches(FixtureCorpus.libraryFor(slug)),
                    FixtureCorpus.libraryFor(slug),
                    slug + " must be unchanged by a second pass, because no patch keys on it");
        }
    }
}
