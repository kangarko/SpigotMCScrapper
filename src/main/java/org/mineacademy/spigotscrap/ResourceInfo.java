package org.mineacademy.spigotscrap;

import java.util.Map;

import lombok.Data;

/**
 * Represents a premium plugin
 */
@Data
public final class ResourceInfo {

	/**
	 * The name of the plugin
	 */
	private final String name;

	/**
	 * The URL to the plugin
	 */
	private final String url;

	/**
	 * The number of purchases - THIS IS NOT PRECISE AS WE EQUATE THIS TO THE AMOUNT OF DOWNLOADS
	 */
	private final int purchases;

	/**
	 * The submission date of the plugin
	 */
	private final long submissionDate;

	/**
	 * Return the number of downloads per day
	 *
	 * @return
	 */
	public double getPurchasesPerDay() {
		if (this.purchases == 0)
			return 0;

		final long diff = System.currentTimeMillis() - this.submissionDate;
		final long days = diff / 1000 / 60 / 60 / 24;

		return (double) this.purchases / (double) days;
	}

	/**
	 * Calculate the days ago from the submission date.
	 *
	 * @param submissionDate
	 * @return
	 */
	public long getPublishedDaysAgo() {
		final long diff = System.currentTimeMillis() - this.submissionDate;
		final long days = diff / 1000 / 60 / 60 / 24;

		return days;
	}

	/**
	 * Serialize this object to a map to be saved in a file
	 *
	 * @return
	 */
	public Map<String, Object> serialize() {
		return Map.of(
				"name", this.name,
				"url", this.url,
				"downloads", this.purchases,
				"submissionDate", this.submissionDate);
	}

	/**
	 * Deserialize this object from a map loaded from a file
	 *
	 * @param map
	 * @return
	 */
	public static ResourceInfo deserialize(Map<String, Object> map) {
		return new ResourceInfo(
				(String) map.get("name"),
				(String) map.get("url"),
				(int) map.get("downloads"),
				Long.parseLong(map.get("submissionDate").toString()));
	}
}