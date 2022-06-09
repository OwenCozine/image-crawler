import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jsoup.nodes.*;
import org.jsoup.*;
import org.jsoup.select.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.concurrent.CountDownLatch;
import java.util.Collections;

@WebServlet(name = "ImageFinder", urlPatterns = { "/main" })
public class ImageFinder extends HttpServlet {
	private static final long serialVersionUID = 1L;


	protected static HashSet<String> linkSet = new HashSet<String>();
	protected static Set<String> imageSet = Collections.synchronizedSet(new HashSet<String>());
	protected static Set<String> unsizedImageSet = Collections.synchronizedSet(new HashSet<String>());
	protected static final Gson GSON = new GsonBuilder().create();


	@Override
	protected final void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("text/json");
		String path = req.getServletPath();
		String url = req.getParameter("url");
		System.out.println("Got request of:" + path + " with query param:" + url);

		// Start Jsoup document
		Document document = null;
		try {
			document = Jsoup.connect(url).get();
		} catch (Exception ex) {
			System.out.println(ex);
			return;
		}

		linkSet.add(url);
		// Get urls from that page
		this.getUrlstoCrawl(document);

		// Want to be able to access link with thread num
		String[] linkArray = new String[linkSet.size()];
		int incr = 0;
		for (String link : linkSet) {
			linkArray[incr] = link;
			incr++;
		}
		// Actually create and run threads
		int latchGroup = linkSet.size();
		CountDownLatch latch = new CountDownLatch(latchGroup);
		for (int i = 0; i < linkSet.size(); i++) {
			ImageCrawler c = new ImageCrawler(imageSet, unsizedImageSet, linkArray, latch);
			Thread t = new Thread(c);
			t.setName(String.valueOf(i));
			t.start();
		}
		// This makes sure all threads are done before continuing
		try {
			latch.await();
		} catch (Exception e) {
			System.out.println("Latch error" + e);
			return;
		}

		System.out.printf("Got %d images from %d sites\n", imageSet.size(), linkSet.size());

		// Get images from the set into an array for JSON
		String[] allImages = new String[imageSet.size()];
		int count = 0;
		for (String str : imageSet) {
			allImages[count] = str;
			count++;
		}

		// give images to site
		resp.getWriter().print(GSON.toJson(allImages));

		// clean up so it can run again
		count = 0;
		imageSet.clear();
		linkSet.clear();
		unsizedImageSet.clear();

	}

	// Get links from the page
	public void getUrlstoCrawl(Document document) {

		Elements links = document.select("a[href]");
		for (Element link : links) {
			// Going to arbitrarily cut this off at 100 as I don't know
			// what my old macbook can handle. Doesn't matter as this is scalable
			// I think JVM's limit for threads is usually around 1024
			if (linkSet.size() > 99)
				break;
			if (linkSet.contains(link.attr("abs:href")))
				continue;
			if (this.isValidUrl(document.location(), link.attr("abs:href")))
				linkSet.add(link.attr("abs:href"));
		}
	}

	// func to make sure we want the Url
	public boolean isValidUrl(String homeUrl, String checkUrl) {
		// Ignore fragment identifiers
		if (checkUrl.contains(String.valueOf('#'))) {
			return false;
		}
		String holdUrl = "";
		if(checkUrl.contains("https://"))
			checkUrl = checkUrl.replace("https://", "");
		
		else if(checkUrl.contains("http://"))
			checkUrl = checkUrl.replace("http://", "");

		else {return false;}

		for (int i = 0; i < checkUrl.length(); i++) {
			if (checkUrl.charAt(i) == '/')
				break;
			holdUrl = holdUrl + String.valueOf(checkUrl.charAt(i));
		}
		// Got empty links occasionally, just gonna patch that here
		// 3 covers .io, i don't know of anything smaller
		if (holdUrl.length() < 3)
			return false;

		if (homeUrl.contains(holdUrl))
			return true;

		return false;
	}

}

// Run multiple crawlers simultaneously for images, speeds up program
class ImageCrawler extends Thread {
	protected static Set<String> imageSet;
	// This is used to stop duplicates of differently sized images
	protected static Set<String> unsizedImageSet;
	protected static String[] linkList;
	protected static CountDownLatch latch;

	ImageCrawler(Set<String> imageS, Set<String> unsizedImageS, String[] links, CountDownLatch latc) {
		imageSet = imageS;
		unsizedImageSet = unsizedImageS;
		linkList = links;
		latch = latc;
	}

	@Override
	public void run() {
		Thread t = Thread.currentThread();
		String name = t.getName();
		imageUrlsFromPage(linkList[Integer.valueOf(name)]);
		latch.countDown();
	}

	public void imageUrlsFromPage(String url) {
		Document document = null;

		try {
			document = Jsoup.connect(url).get();
		} catch (Exception ex) {
			System.out.println(ex);
			return;
		}

		Elements images = document.select("img");
		// couldnt get normal for loop to iterate this, so count iterations
		for (Element el : images) {
			String imageUrl = el.attr("src");
			if(!isValidImage(imageUrl)) 
				continue;
			// This regex removes the sizing on the end of the img url
			if (unsizedImageSet.contains(imageUrl.replaceAll("/\\d+px.*", "")))
				continue;
			unsizedImageSet.add(imageUrl.replaceAll("/\\d+px.*", ""));
			imageSet.add(imageUrl);
		}
		return;
	}

	//I was getting images without protocol, which couldn't load on js site, so ignore those
	public boolean isValidImage(String url){
		if(url.contains("https://") || url.contains("http://"))
			return true;
		return false;
	}
}
