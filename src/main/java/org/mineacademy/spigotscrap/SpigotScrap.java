package org.mineacademy.spigotscrap;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.htmlunit.BrowserVersion;
import org.htmlunit.CookieManager;
import org.htmlunit.WebClient;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.DomNodeList;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.Cookie;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

/**
 * The main class of SpigotMC scrapper.
 */
public final class SpigotScrap {

	/**
	 * Starts the program
	 *
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			final SpigotScrap main = new SpigotScrap();

			main.loadConfig();
			main.connect();
			//main.printResults();

		} catch (final Throwable t) {
			t.printStackTrace();
		}
	}

	/**
	 * The start page to begin parsing from.
	 */
	public static final int START_PAGE = 1;

	/**
	 * The page to stop parsing at.
	 */
	public static final int END_PAGE = 80;

	/**
	 * The limit of resources to print to the console.
	 */
	public static final int RESOURCE_LIST_LIMIT = 500;

	/**
	 * The limit of days ago for resources to be considered.
	 */
	public static final int RESOURCE_PUBLISHED_DAYS_AGO_LIMIT = 365;

	/**
	 * The cookies to bypass the Cloudflare protection and logs us in automatically.
	 */
	public static final String COOKIE_CLOUDFLARE = "";
	public static final String COOKIE_XENFORO_SESSION = "";
	public static final String COOKIE_XENFORO_USER = "";

	/**
	 * The user agent to use when connecting to the website.
	 */
	public static final String USER_AGENT = "PUT YOUR USER AGENT HERE";

	/**
	 * The database file, in YAML format. We use the YamlConfiguration from the BungeeCord API to manage this file.
	 */
	private final File file = new File("database.yml");

	/**
	 * The resources we have parsed so far or loaded from the file.
	 */
	private final Map<String, ResourceInfo> resources = new HashMap<>();

	/**
	 * The configuration for the database file.
	 */
	private Configuration config;

	/**
	 * Load the configuration file and parse the resources from it.
	 *
	 * @throws Throwable
	 */
	public void loadConfig() throws Throwable {
		if (!this.file.exists())
			this.file.createNewFile();

		this.config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(this.file);

		// We know that the saved list is a list of maps because when saving it, we convert the ResourceInfo to a map
		for (final Object resourceRaw : this.config.getList("resources")) {
			final Map<String, Object> resourceMap = (Map<String, Object>) resourceRaw;
			final ResourceInfo resource = ResourceInfo.deserialize(resourceMap);

			this.resources.put(resource.getUrl(), resource);
		}
	}

	/**
	 * Connect to the SpigotMC website and start parsing the resources.
	 *
	 * @throws Throwable
	 */
	public void connect() throws Throwable {

		// Initialize the web client using my Chrome browser version, timezone and user agent
		final WebClient client = new WebClient(new BrowserVersion.BrowserVersionBuilder(BrowserVersion.CHROME)
				.setUserAgent(USER_AGENT)
				.build());

		// Disable unnecessary logging
		java.util.logging.Logger.getLogger("org.htmlunit").setLevel(Level.OFF);
		System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");

		// Set the client options to disable unnecessary features and improve performance
		client.getOptions().setJavaScriptEnabled(false);
		client.getOptions().setCssEnabled(false);
		client.getOptions().setDownloadImages(false);

		// Follow redirects, fixes Cloudflare redirect issues
		client.getOptions().setRedirectEnabled(true);

		// Load cookies from our browser into our client
		final CookieManager cookies = new CookieManager();

		cookies.addCookie(new Cookie("spigotmc.org", "cf_clearance", COOKIE_CLOUDFLARE));
		cookies.addCookie(new Cookie("spigotmc.org", "xf_session", COOKIE_XENFORO_SESSION));
		cookies.addCookie(new Cookie("spigotmc.org", "xf_user", COOKIE_XENFORO_USER));

		client.setCookieManager(cookies);

		// Start parsing the resources
		this.parse(client, START_PAGE);

		// Close the client once done
		client.close();
	}

	/**
	 * Parse the resources from the SpigotMC website and store them in our database.
	 *
	 * @param client
	 * @param pageNumber
	 * @throws Throwable
	 */
	private void parse(WebClient client, int pageNumber) throws Throwable {

		// SpigotMC has 80 pages at the time of this writing, so we stop at 79
		if (pageNumber >= END_PAGE) {
			this.printResults();

			return;
		}

		System.out.println("Parsing page " + pageNumber);

		// Connect to the SpigotMC premium resources listing page, order by date submitted and list resources by the page number
		final HtmlPage page = client.getPage("https://www.spigotmc.org/resources/categories/premium.20/?order=resource_date&page=" + pageNumber);

		// Get all the resource boxes on the page using the CSS selector
		final DomNodeList<DomNode> boxes = page.querySelectorAll(".resourceListItem.visible");

		for (final DomNode row : boxes) {

			// Get the resource name, url, purchases and submission date
			final String name = this.cleanTitle(row.querySelector(".title a").getTextContent()).trim();
			final String url = "https://spigotmc.org/" + this.removeTextFromUrl(((HtmlAnchor) row.querySelector(".title a")).getAttribute("href"));
			final int purchases = Integer.parseInt(row.querySelector(".resourceDownloads dd").getTextContent().replace(",", ""));
			final String submissionRaw = ((DomElement) row.querySelector(".resourceDetails .DateTime")).getAttribute("title");

			// The submission date is in the format of "Jan 01, 2022 at 12:00 AM", we convert it to Unix timestamp
			final long submissionDate = this.toUnix(submissionRaw);

			// Skip if the resource is already in the database
			if (this.resources.containsKey(url)) {
				System.out.println("Skipping resource " + name + " as it is already in the database");

				continue;
			}

			final ResourceInfo resource = new ResourceInfo(name, url, purchases, submissionDate);

			System.out.println("Loading '" + name + "' - " + purchases + " purchases - " + this.formatUnix(submissionDate) + " (" + resource.getPublishedDaysAgo() + " days ago)");
			this.resources.put(url, resource);
		}

		// Save the database after each page
		this.saveConfig();

		// Continue to the next page
		this.parse(client, pageNumber + 1);
	}

	/**
	 * Print the results of the parsed resources.
	 *
	 * @throws Throwable
	 */
	public void printResults() throws Throwable {
		List<ResourceInfo> resources = new ArrayList<>(this.resources.values());

		System.out.println("Total resources: " + resources.size());

		// Remove resources with no purchases
		resources.removeIf(info -> info.getPurchases() == 0);

		// Remove resources older than the given limit
		resources.removeIf(info -> info.getPublishedDaysAgo() > RESOURCE_PUBLISHED_DAYS_AGO_LIMIT);

		// Sort the resources by purchases per day
		resources.sort(Comparator.comparing(ResourceInfo::getPurchasesPerDay).reversed());

		// Limit the resources to 500
		if (resources.size() > RESOURCE_LIST_LIMIT)
			resources = resources.subList(0, RESOURCE_LIST_LIMIT);

		System.out.println("Printing: " + resources.size() + " resources (sorted by purchases per day)");

		for (final ResourceInfo resource : resources) {

			// Shorten the resource name, removing - and | such as "MythicChanger-Premium | Match and modify all your items without trouble" becomes "MythicChanger-Premium"
			final String[] nameSplit = resource.getName().split(" \\- ")[0].split(" \\| ")[0].split(" ");
			final String name = nameSplit.length > 1 ? nameSplit[0] + " " + nameSplit[1] : nameSplit[0];

			// Format days ago with leading spaces to three places, i.e. 1 is "  1", 10 is " 10" and 100 is "100"
			final long daysAgo = resource.getPublishedDaysAgo();
			String daysAgoFormatted = String.valueOf(daysAgo);

			if (daysAgo < 10)
				daysAgoFormatted = "  " + daysAgoFormatted;
			else if (daysAgo < 100)
				daysAgoFormatted = " " + daysAgoFormatted;

			final String downloads = String.format("%02d", resource.getPurchases());
			final String downloadsPerDay = String.format("%.2f", resource.getPurchasesPerDay());

			System.out.printf("%-50s%s%n", "- " + name, daysAgoFormatted + " days ago - " + downloads + " purchases = " + downloadsPerDay + " purchases/day");
		}

		System.out.println("Printing complete.");
	}

	/**
	 * Save the configuration file to disk.
	 *
	 * @throws Throwable
	 */
	private void saveConfig() throws Throwable {
		final List<Map<String, Object>> resources = new ArrayList<>();

		for (final ResourceInfo resource : this.resources.values())
			resources.add(resource.serialize());

		this.config.set("resources", resources);
		ConfigurationProvider.getProvider(YamlConfiguration.class).save(this.config, this.file);
	}

	/**
	 * Clean the title of the resource, removing unnecessary text.
	 *
	 * @param message
	 * @return
	 */
	private String cleanTitle(String message) {
		final String[] patterns = {
				"\\[[0-9]+% OFF\\]", // [25% OFF] or any percentage amount in brackets
				"[0-9]+% OFF", // 20% OFF or any percentage amount
				"\\[[0-9]+(\\.[0-9]+)*(\\.x)?\\s?-\\s?[0-9]+(\\.[0-9]+)*(\\.x)?\\]", // [1.12 - 1.20], [1.8-1.20.6], [1.16.x - 1.20.x] or any version numbers including subversion
				"[\\p{So}\\p{Cn}]", // Any unicode or emojis
				"[0-9]+(\\.[0-9]+)*(\\.x)?\\s?-\\s?[0-9]+(\\.[0-9]+)*(\\.x)?" // 1.17-1.19, 1.8-1.20.6, 1.16.x-1.20.x without brackets
		};

		for (final String pattern : patterns)
			message = message.replaceAll(pattern, "");

		// Remove unnecessary or duplicated spaces
		message = message.replaceAll("\\s+", " ").trim();

		// Extract title if message contains '-' or '|'
		message = message.split("[-|]", 2)[0].trim();

		// If message has a double space, i.e. "SystemInfo Hardware Monitor ️ Enterprise Quality", only return the first part i.e "SystemInfo Hardware Monitor"
		if (message.contains("  "))
			message = message.split("  ", 2)[0].trim();

		if (message.startsWith(" "))
			message = message.substring(1);

		return message;
	}

	/**
	 * Remove the text from the URL, i.e. /resources/my-resource.1234/ becomes /resources/1234
	 *
	 * @param url
	 * @return
	 */
	private String removeTextFromUrl(String url) {
		final int lastSlash = url.lastIndexOf('/');
		final int dotIndex = url.lastIndexOf('.');

		if (lastSlash != -1 && dotIndex != -1 && dotIndex > lastSlash)
			return url.substring(0, lastSlash + 1) + url.substring(dotIndex + 1);

		return url;
	}

	/**
	 * Convert the date string to Unix timestamp.
	 *
	 * @param dateStr
	 * @return
	 * @throws ParseException
	 */
	private long toUnix(String dateStr) throws ParseException {
		final SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.ENGLISH);
		final Date date = sdf.parse(dateStr);

		return date.getTime();
	}

	/**
	 * Format the Unix timestamp to a human-readable date.
	 *
	 * @param unix
	 * @return
	 */
	private String formatUnix(long unix) {
		final Date date = new Date(unix);
		final SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH);

		return sdf.format(date);
	}
}
