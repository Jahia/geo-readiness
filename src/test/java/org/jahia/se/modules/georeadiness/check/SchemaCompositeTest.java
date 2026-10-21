package org.jahia.se.modules.georeadiness.check;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Values made of several properties, opening hours and the type suggestion, on data only. */
class SchemaCompositeTest {

    /** The properties of a store locator's store type, as its CND declares them. */
    private static final Set<String> STORE = new LinkedHashSet<>(Arrays.asList(
            "name", "description", "url", "telephone", "image", "priceRange", "amenityFeature", "openingHours",
            "streetAddress", "addressLocality", "addressRegion", "postalCode", "addressCountry",
            "latitude", "longitude", "jcr:title", "jcr:created", "jcr:lastModified"));

    @Test
    void anAddressSplitIntoSchemaOrgFieldsIsASource() {
        assertThat(StructuredData.hasSource(STORE, "address")).isTrue();
        assertThat(StructuredData.hasSource(STORE, "geo")).isTrue();
        assertThat(StructuredData.hasSource(new LinkedHashSet<>(Arrays.asList("postalCode", "addressCountry")), "address"))
                .as("a postal code and a country do not locate anything").isFalse();
        assertThat(StructuredData.hasSource(new LinkedHashSet<>(Arrays.asList("latitude")), "geo"))
                .as("one coordinate is not a position").isFalse();
    }

    @Test
    void aPostalAddressKeepsSchemaOrgFieldNames() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("streetAddress", "21 cours des Vignes");
        parts.put("addressLocality", "Bordeaux");
        parts.put("postalCode", "33000");
        parts.put("addressCountry", "FR");
        JSONObject address = StructuredData.composite(SchemaMap.compositeFor("address"), parts);
        assertThat(address.getString("@type")).isEqualTo("PostalAddress");
        assertThat(address.getString("addressLocality")).isEqualTo("Bordeaux");
        assertThat(address.has("addressRegion")).isFalse();
    }

    @Test
    void coordinatesAreNumbersOrNothing() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("latitude", "44.8378");
        parts.put("longitude", "-0.5792");
        JSONObject geo = StructuredData.composite(SchemaMap.compositeFor("geo"), parts);
        assertThat(geo.getString("@type")).isEqualTo("GeoCoordinates");
        assertThat(geo.getDouble("longitude")).isEqualTo(-0.5792);
        parts.put("latitude", "north");
        assertThat(StructuredData.composite(SchemaMap.compositeFor("geo"), parts)).isNull();
    }

    @Test
    void openingHoursStoredAsJsonBecomeASpecification() {
        JSONObject hours = StructuredData.openingHours(Arrays.asList(
                "{\"dayOfWeek\": \"Monday\", \"opens\": \"09:00\", \"closes\": \"18:00\"}",
                "{\"dayOfWeek\": \"Saturday\", \"opens\": \"09:00\", \"closes\": \"13:00\"}",
                "{\"dayOfWeek\": \"<script>\", \"opens\": \"x\", \"closes\": \"y\"}"));
        assertThat(hours.getJSONArray("openingHoursSpecification").length()).isEqualTo(2);
        assertThat(hours.getJSONArray("openingHoursSpecification").getJSONObject(1).getString("closes")).isEqualTo("13:00");
    }

    @Test
    void openingHoursAsTextStayText() {
        JSONObject hours = StructuredData.openingHours(Arrays.asList("Mo-Fr 09:00-18:00", ""));
        assertThat(hours.getJSONArray("openingHours").length()).isEqualTo(1);
        assertThat(StructuredData.openingHours(Arrays.asList("", "  "))).isNull();
    }

    @Test
    void anAddressWithATelephoneSuggestsALocalBusiness() {
        assertThat(SchemaMap.suggestFor(STORE)).isEqualTo("LocalBusiness");
        assertThat(SchemaMap.suggestFor(new LinkedHashSet<>(Arrays.asList("name", "streetAddress", "addressLocality"))))
                .isEqualTo("Place");
        assertThat(SchemaMap.suggestFor(new LinkedHashSet<>(Arrays.asList("name", "latitude", "longitude"))))
                .isEqualTo("Place");
        assertThat(SchemaMap.suggestFor(new LinkedHashSet<>(Arrays.asList("title", "body", "date")))).isNull();
    }

    @Test
    void aLocalBusinessRecommendsWhatAStoreHolds() {
        assertThat(SchemaMap.recommendedFor("LocalBusiness")).contains("geo", "openingHours", "priceRange", "telephone");
        for (String p : SchemaMap.recommendedFor("LocalBusiness")) {
            if (!"email".equals(p)) {
                assertThat(StructuredData.hasSource(STORE, p)).as(p).isTrue();
            }
        }
    }
}
