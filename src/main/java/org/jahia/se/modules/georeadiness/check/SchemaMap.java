package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GEO-23. Which schema.org type each content type is, and what each schema
 * property can be filled from.
 *
 * **Defaults only where we actually know.** Jahia's own types have one right
 * answer - a page is a WebPage - so those are built in. A custom type is
 * somebody's model and nothing here can read their intent from its name:
 * guessing that `luxe:estate` is a Product because it has "estate" in it would
 * be the same class of mistake as deciding which content is evergreen from its
 * type name. Custom types start unmapped, are reported as unmapped, and are
 * mapped by the person who defined them.
 *
 * **Property sources are conventions, not guesses.** `title`, `description`,
 * `image`, `date`, `price`, `address`, `phone`, `email` mean the same thing
 * across almost every content model, so a candidate list per schema property
 * finds them without anyone configuring anything. Where no candidate matches,
 * the property is reported as having no source - which is the point, and the
 * one thing the story is explicit about: say so rather than invent one.
 */
public final class SchemaMap {

    /** Jahia's own types, where the answer is not a matter of opinion. */
    private static final Map<String, String> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("jnt:page", "WebPage");
        BUILT_IN.put("jnt:file", "MediaObject");
        BUILT_IN.put("jnt:news", "NewsArticle");
        BUILT_IN.put("jnt:event", "Event");
        BUILT_IN.put("jnt:blogPost", "BlogPosting");
        BUILT_IN.put("jnt:person", "Person");
    }

    /**
     * The types offered in the mapping interface. Deliberately short: a list of
     * every schema.org type would be unusable, and these cover what a CMS
     * actually holds.
     */
    public static final List<String> OFFERED = Arrays.asList(
            "WebPage", "Article", "BlogPosting", "NewsArticle", "Product", "Event",
            "Organization", "LocalBusiness", "Person", "Place", "Service", "Offer",
            "FAQPage", "Recipe", "Course", "JobPosting", "VideoObject", "ImageObject");

    /**
     * What each schema type needs, and what merely helps.
     *
     * "Required" here means required for a rich result, which is the standard
     * that has consequences. Claiming schema.org's own cardinality would be
     * technically right and practically useless: almost nothing is required by
     * the vocabulary itself.
     */
    private static final Map<String, String[]> REQUIRED = new LinkedHashMap<>();
    private static final Map<String, String[]> RECOMMENDED = new LinkedHashMap<>();

    static {
        REQUIRED.put("WebPage", new String[]{"name"});
        REQUIRED.put("Article", new String[]{"headline", "datePublished"});
        REQUIRED.put("BlogPosting", new String[]{"headline", "datePublished"});
        REQUIRED.put("NewsArticle", new String[]{"headline", "datePublished"});
        REQUIRED.put("Product", new String[]{"name", "image"});
        REQUIRED.put("Event", new String[]{"name", "startDate", "location"});
        REQUIRED.put("Organization", new String[]{"name"});
        REQUIRED.put("LocalBusiness", new String[]{"name", "address"});
        REQUIRED.put("Person", new String[]{"name"});
        REQUIRED.put("Place", new String[]{"name", "address"});
        REQUIRED.put("Service", new String[]{"name"});
        REQUIRED.put("Offer", new String[]{"price", "priceCurrency"});
        REQUIRED.put("FAQPage", new String[]{"name"});
        REQUIRED.put("Recipe", new String[]{"name", "image"});
        REQUIRED.put("Course", new String[]{"name", "description"});
        REQUIRED.put("JobPosting", new String[]{"title", "datePosted"});
        REQUIRED.put("VideoObject", new String[]{"name", "uploadDate"});
        REQUIRED.put("ImageObject", new String[]{"contentUrl"});

        RECOMMENDED.put("WebPage", new String[]{"description", "dateModified"});
        RECOMMENDED.put("Article", new String[]{"image", "author", "description", "dateModified"});
        RECOMMENDED.put("BlogPosting", new String[]{"image", "author", "description", "dateModified"});
        RECOMMENDED.put("NewsArticle", new String[]{"image", "author", "description", "dateModified"});
        // priceCurrency after offers, so the Offer exists by the time it is
        // added - and so a model with a price but no currency is told, since a
        // price without one is ambiguous to every consumer of it.
        RECOMMENDED.put("Product", new String[]{"description", "offers", "priceCurrency", "brand"});
        RECOMMENDED.put("Event", new String[]{"description", "image", "endDate"});
        RECOMMENDED.put("Organization", new String[]{"description", "image", "address", "telephone", "email"});
        RECOMMENDED.put("LocalBusiness", new String[]{"description", "image", "telephone", "email"});
        RECOMMENDED.put("Person", new String[]{"description", "image", "jobTitle", "telephone", "email"});
        RECOMMENDED.put("Place", new String[]{"description", "image"});
        RECOMMENDED.put("Service", new String[]{"description", "image"});
    }

    /**
     * Where each schema property can come from, in order of preference.
     *
     * Matched case-insensitively against the property names a type actually
     * declares, so a model using `name` and one using `title` both work without
     * either being configured.
     */
    private static final Map<String, String[]> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put("name", new String[]{"jcr:title", "title", "name", "fullName", "label"});
        SOURCES.put("headline", new String[]{"jcr:title", "title", "headline", "name"});
        SOURCES.put("title", new String[]{"jcr:title", "title", "jobPosition", "jobTitle"});
        SOURCES.put("description", new String[]{"description", "jcr:description", "summary",
                "subtitle", "abstract", "intro", "teaser", "body"});
        SOURCES.put("image", new String[]{"image", "images", "picture", "photo", "thumbnail", "visual"});
        SOURCES.put("contentUrl", new String[]{"image", "file", "video"});
        SOURCES.put("datePublished", new String[]{"date", "publicationDate", "publishedOn",
                "creationDate", "jcr:created"});
        SOURCES.put("datePosted", new String[]{"date", "publicationDate", "jcr:created"});
        SOURCES.put("uploadDate", new String[]{"date", "jcr:created"});
        SOURCES.put("dateModified", new String[]{"jcr:lastModified"});
        SOURCES.put("startDate", new String[]{"startDate", "start", "date", "eventDate"});
        SOURCES.put("endDate", new String[]{"endDate", "end"});
        SOURCES.put("price", new String[]{"price", "amount", "cost"});
        SOURCES.put("priceCurrency", new String[]{"currency", "priceCurrency"});
        SOURCES.put("address", new String[]{"address", "street", "location"});
        SOURCES.put("telephone", new String[]{"phone", "telephone", "tel", "mobile"});
        SOURCES.put("email", new String[]{"email", "mail"});
        SOURCES.put("jobTitle", new String[]{"jobPosition", "jobTitle", "position", "role"});
        SOURCES.put("author", new String[]{"author", "writtenBy", "realtor"});
        SOURCES.put("brand", new String[]{"brand", "manufacturer"});
        SOURCES.put("location", new String[]{"location", "venue", "address", "place"});
        SOURCES.put("offers", new String[]{"price"});
    }

    private SchemaMap() {
    }

    /** The schema type for a node type: the site's choice, else a built-in, else none. */
    public static String typeFor(JSONObject overrides, String nodeType) {
        if (overrides != null) {
            String chosen = overrides.optString(nodeType, "");
            if (!chosen.isEmpty()) {
                return "none".equals(chosen) ? null : chosen;
            }
        }
        return BUILT_IN.get(nodeType);
    }

    /** True when the site has explicitly said this type maps to nothing. */
    public static boolean muted(JSONObject overrides, String nodeType) {
        return overrides != null && "none".equals(overrides.optString(nodeType, ""));
    }

    public static String[] requiredFor(String schemaType) {
        return REQUIRED.getOrDefault(schemaType, new String[]{"name"});
    }

    public static String[] recommendedFor(String schemaType) {
        return RECOMMENDED.getOrDefault(schemaType, new String[0]);
    }

    public static String[] sourcesFor(String schemaProperty) {
        return SOURCES.getOrDefault(schemaProperty, new String[]{schemaProperty});
    }

    /** Everything the mapping interface needs to render itself. */
    public static JSONObject vocabulary() {
        JSONObject out = new JSONObject();
        out.put("types", new JSONArray(OFFERED));
        JSONObject builtIn = new JSONObject();
        BUILT_IN.forEach(builtIn::put);
        out.put("builtIn", builtIn);
        return out;
    }
}
