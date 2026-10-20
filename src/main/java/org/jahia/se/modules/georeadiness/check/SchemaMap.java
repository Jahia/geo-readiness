package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * **Some values are made of several properties.** A model that follows
 * schema.org field by field (a store locator's `streetAddress`, `postalCode`,
 * `addressLocality`...) holds a PostalAddress without any property called
 * `address`. {@link #compositeFor} names those parts, so such a model fills
 * `address` and `geo` instead of being reported as having no source for them.
 *
 * **A suggestion is a hint, never a mapping.** {@link #suggestFor} reads the
 * properties a type declares (an address, a telephone, opening hours), never
 * its name, and the panel only offers it: the person who owns the model still
 * chooses.
 */
public final class SchemaMap {

    // Names used across the tables below.
    private static final String WEB_PAGE = "WebPage";
    private static final String NEWS_ARTICLE = "NewsArticle";
    private static final String EVENT = "Event";
    private static final String BLOG_POSTING = "BlogPosting";
    private static final String PERSON = "Person";
    private static final String ARTICLE = "Article";
    private static final String PRODUCT = "Product";
    private static final String ORGANIZATION = "Organization";
    private static final String SERVICE = "Service";
    private static final String NAME = "name";
    private static final String HEADLINE = "headline";
    private static final String DATE_PUBLISHED = "datePublished";
    private static final String START_DATE = "startDate";
    private static final String LOCATION = "location";
    private static final String PRICE = "price";
    private static final String PRICE_CURRENCY = "priceCurrency";
    private static final String TITLE = "title";
    private static final String DATE_MODIFIED = "dateModified";
    private static final String AUTHOR = "author";
    private static final String BRAND = "brand";
    private static final String END_DATE = "endDate";
    private static final String GEO = "geo";
    private static final String JOB_TITLE = "jobTitle";
    private static final String JCR_TITLE = "jcr:title";
    private static final String DATE = "date";
    private static final String JCR_CREATED = "jcr:created";
    private static final String TELEPHONE = "telephone";
    private static final String LOCAL_BUSINESS = "LocalBusiness";
    private static final String DESCRIPTION = "description";
    private static final String EMAIL = "email";
    private static final String PRICE_RANGE = "priceRange";
    private static final String OPENING_HOURS = "openingHours";
    private static final String ADDRESS = "address";
    private static final String IMAGE = "image";
    private static final String PLACE = "Place";

    /** Jahia's own types, where the answer is not a matter of opinion. */
    private static final Map<String, String> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("jnt:page", WEB_PAGE);
        BUILT_IN.put("jnt:file", "MediaObject");
        BUILT_IN.put("jnt:news", NEWS_ARTICLE);
        BUILT_IN.put("jnt:event", EVENT);
        BUILT_IN.put("jnt:blogPost", BLOG_POSTING);
        BUILT_IN.put("jnt:person", PERSON);
    }

    /**
     * The types offered in the mapping interface. Deliberately short: a list of
     * every schema.org type would be unusable, and these cover what a CMS
     * actually holds.
     */
    public static final List<String> OFFERED = Arrays.asList(
            WEB_PAGE, ARTICLE, BLOG_POSTING, NEWS_ARTICLE, PRODUCT, EVENT,
            ORGANIZATION, LOCAL_BUSINESS, PERSON, PLACE, SERVICE, "Offer",
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
        REQUIRED.put(WEB_PAGE, new String[]{NAME});
        REQUIRED.put(ARTICLE, new String[]{HEADLINE, DATE_PUBLISHED});
        REQUIRED.put(BLOG_POSTING, new String[]{HEADLINE, DATE_PUBLISHED});
        REQUIRED.put(NEWS_ARTICLE, new String[]{HEADLINE, DATE_PUBLISHED});
        REQUIRED.put(PRODUCT, new String[]{NAME, IMAGE});
        REQUIRED.put(EVENT, new String[]{NAME, START_DATE, LOCATION});
        REQUIRED.put(ORGANIZATION, new String[]{NAME});
        REQUIRED.put(LOCAL_BUSINESS, new String[]{NAME, ADDRESS});
        REQUIRED.put(PERSON, new String[]{NAME});
        REQUIRED.put(PLACE, new String[]{NAME, ADDRESS});
        REQUIRED.put(SERVICE, new String[]{NAME});
        REQUIRED.put("Offer", new String[]{PRICE, PRICE_CURRENCY});
        REQUIRED.put("FAQPage", new String[]{NAME});
        REQUIRED.put("Recipe", new String[]{NAME, IMAGE});
        REQUIRED.put("Course", new String[]{NAME, DESCRIPTION});
        REQUIRED.put("JobPosting", new String[]{TITLE, "datePosted"});
        REQUIRED.put("VideoObject", new String[]{NAME, "uploadDate"});
        REQUIRED.put("ImageObject", new String[]{"contentUrl"});

        RECOMMENDED.put(WEB_PAGE, new String[]{DESCRIPTION, DATE_MODIFIED});
        RECOMMENDED.put(ARTICLE, new String[]{IMAGE, AUTHOR, DESCRIPTION, DATE_MODIFIED});
        RECOMMENDED.put(BLOG_POSTING, new String[]{IMAGE, AUTHOR, DESCRIPTION, DATE_MODIFIED});
        RECOMMENDED.put(NEWS_ARTICLE, new String[]{IMAGE, AUTHOR, DESCRIPTION, DATE_MODIFIED});
        // priceCurrency after offers, so the Offer exists by the time it is
        // added - and so a model with a price but no currency is told, since a
        // price without one is ambiguous to every consumer of it.
        RECOMMENDED.put(PRODUCT, new String[]{DESCRIPTION, "offers", PRICE_CURRENCY, BRAND});
        RECOMMENDED.put(EVENT, new String[]{DESCRIPTION, IMAGE, END_DATE});
        RECOMMENDED.put(ORGANIZATION, new String[]{DESCRIPTION, IMAGE, ADDRESS, TELEPHONE, EMAIL});
        RECOMMENDED.put(LOCAL_BUSINESS, new String[]{DESCRIPTION, IMAGE, TELEPHONE, EMAIL, GEO,
                OPENING_HOURS, PRICE_RANGE});
        RECOMMENDED.put(PERSON, new String[]{DESCRIPTION, IMAGE, JOB_TITLE, TELEPHONE, EMAIL});
        RECOMMENDED.put(PLACE, new String[]{DESCRIPTION, IMAGE, GEO, TELEPHONE});
        RECOMMENDED.put(SERVICE, new String[]{DESCRIPTION, IMAGE});
    }

    /**
     * Where each schema property can come from, in order of preference.
     *
     * Matched case-insensitively against the property names a type actually
     * declares, so a model using `name` and one using `title` both work without
     * either being configured.
     */
    private static final Map<String, String[]> SOURCES = new LinkedHashMap<>();
    /** Values built from several properties; filled with SOURCES below. */
    private static final Map<String, Composite> COMPOSITES = new LinkedHashMap<>();

    static {
        SOURCES.put(NAME, new String[]{JCR_TITLE, TITLE, NAME, "fullName", "label"});
        SOURCES.put(HEADLINE, new String[]{JCR_TITLE, TITLE, HEADLINE, NAME});
        SOURCES.put(TITLE, new String[]{JCR_TITLE, TITLE, "jobPosition", JOB_TITLE});
        SOURCES.put(DESCRIPTION, new String[]{DESCRIPTION, "jcr:description", "summary",
                "subtitle", "abstract", "intro", "teaser", "body"});
        SOURCES.put(IMAGE, new String[]{IMAGE, "images", "picture", "photo", "thumbnail", "visual"});
        SOURCES.put("contentUrl", new String[]{IMAGE, "file", "video"});
        SOURCES.put(DATE_PUBLISHED, new String[]{DATE, "publicationDate", "publishedOn",
                "creationDate", JCR_CREATED});
        SOURCES.put("datePosted", new String[]{DATE, "publicationDate", JCR_CREATED});
        SOURCES.put("uploadDate", new String[]{DATE, JCR_CREATED});
        SOURCES.put(DATE_MODIFIED, new String[]{"jcr:lastModified"});
        SOURCES.put(START_DATE, new String[]{START_DATE, "start", DATE, "eventDate"});
        SOURCES.put(END_DATE, new String[]{END_DATE, "end"});
        SOURCES.put(PRICE, new String[]{PRICE, "amount", "cost"});
        SOURCES.put(PRICE_CURRENCY, new String[]{"currency", PRICE_CURRENCY});
        SOURCES.put(ADDRESS, new String[]{ADDRESS, "street", LOCATION});
        SOURCES.put(TELEPHONE, new String[]{"phone", TELEPHONE, "tel", "mobile"});
        SOURCES.put(EMAIL, new String[]{EMAIL, "mail"});
        SOURCES.put(JOB_TITLE, new String[]{"jobPosition", JOB_TITLE, "position", "role"});
        SOURCES.put(AUTHOR, new String[]{AUTHOR, "writtenBy", "realtor"});
        SOURCES.put(BRAND, new String[]{BRAND, "manufacturer"});
        SOURCES.put(LOCATION, new String[]{LOCATION, "venue", ADDRESS, "place"});
        SOURCES.put("offers", new String[]{PRICE});
        SOURCES.put(PRICE_RANGE, new String[]{PRICE_RANGE});
        SOURCES.put(OPENING_HOURS, new String[]{OPENING_HOURS, "openingHoursSpecification", "hours"});

        COMPOSITES.put(ADDRESS, new Composite("PostalAddress",
                new String[]{"streetAddress", "addressLocality", "addressRegion", "postalCode", "addressCountry"},
                false, "streetAddress", "addressLocality"));
        COMPOSITES.put(GEO, new Composite("GeoCoordinates", new String[]{"latitude", "longitude"},
                true, "latitude", "longitude"));
    }

    /**
     * A schema.org value built from several content properties, named as schema.org names its
     * own fields.
     */
    public static final class Composite {
        /** The schema.org type of the built value, such as PostalAddress. */
        public final String type;
        /** The content properties that make it, in the order they are written. */
        public final List<String> parts;
        /** True when every anchor must be present, false when one is enough. */
        public final boolean allAnchors;
        /** The parts without which the value means nothing (a street or a town; both coordinates). */
        public final List<String> anchors;

        Composite(String type, String[] parts, boolean allAnchors, String... anchors) {
            this.type = type;
            this.parts = Arrays.asList(parts);
            this.allAnchors = allAnchors;
            this.anchors = Arrays.asList(anchors);
        }

        /** Whether the given part names (as a type declares or a node holds them) make this value. */
        public boolean madeOf(Set<String> present) {
            int found = 0;
            for (String anchor : anchors) {
                if (declares(present, anchor) != null) {
                    found++;
                }
            }
            return allAnchors ? found == anchors.size() : found > 0;
        }
    }

    /** The composite a schema property can be built as, or null. */
    public static Composite compositeFor(String schemaProperty) {
        return COMPOSITES.get(schemaProperty);
    }

    /** The declared name matching {@code name} case-insensitively, or null. */
    public static String declares(Set<String> declared, String name) {
        for (String d : declared) {
            if (d.equalsIgnoreCase(name)) {
                return d;
            }
        }
        return null;
    }

    /**
     * The schema type a model's properties point to, as a hint for the mapping: an address with a
     * telephone, opening hours or a price range is a LocalBusiness; an address or coordinates alone
     * are a Place. Null when the properties say nothing either way.
     *
     * @param declared the property names a content type declares
     * @return LOCAL_BUSINESS, PLACE or null
     */
    public static String suggestFor(Set<String> declared) {
        boolean address = COMPOSITES.get(ADDRESS).madeOf(declared) || declares(declared, ADDRESS) != null;
        boolean geo = COMPOSITES.get(GEO).madeOf(declared);
        if (!address && !geo) {
            return null;
        }
        boolean business = declares(declared, TELEPHONE) != null || declares(declared, OPENING_HOURS) != null
                || declares(declared, PRICE_RANGE) != null;
        return address && business ? LOCAL_BUSINESS : PLACE;
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
        return REQUIRED.getOrDefault(schemaType, new String[]{NAME});
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
