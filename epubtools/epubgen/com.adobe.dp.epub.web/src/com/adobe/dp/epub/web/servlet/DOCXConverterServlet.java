/*******************************************************************************
 * Copyright (c) 2009, Adobe Systems Incorporated
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 *
 * �        Redistributions of source code must retain the above copyright 
 *          notice, this list of conditions and the following disclaimer. 
 *
 * �        Redistributions in binary form must reproduce the above copyright 
 *		   notice, this list of conditions and the following disclaimer in the
 *		   documentation and/or other materials provided with the distribution. 
 *
 * �        Neither the name of Adobe Systems Incorporated nor the names of its 
 *		   contributors may be used to endorse or promote products derived from
 *		   this software without specific prior written permission. 
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR 
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY 
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS 
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/

package com.adobe.dp.epub.web.servlet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.adobe.dp.epub.io.OCFContainerWriter;
import com.adobe.dp.epub.io.ZipContainerSource;
import com.adobe.dp.epub.opf.Publication;
import com.adobe.dp.epub.otf.FontEmbeddingReport;
import com.adobe.dp.epub.util.Translit;
import com.adobe.dp.epub.web.font.FontCookieSet;
import com.adobe.dp.epub.web.font.SharedFontSet;
import com.adobe.dp.epub.web.util.Initializer;
import com.adobe.dp.office.conv.DOCXConverter;
import com.adobe.dp.office.word.WordDocument;
import com.adobe.dp.otf.FontLocator;

public class DOCXConverterServlet extends HttpServlet {
	public static final long serialVersionUID = 0;

	static Logger logger;

	static HashSet activeStreams = new HashSet();

	static {
		logger = Logger.getLogger(DOCXConverterServlet.class);
		logger.setLevel(Level.ALL);
		logger.trace("servlet loaded");
	}

	void reportError(HttpServletResponse resp, String err) throws IOException {
		logger.error(err);
		resp.setContentType("text/plain; charset=utf8");
		Writer out = resp.getWriter();
		out.write(err);
	}

	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doRequest(false, req, resp);
	}

	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doRequest(true, req, resp);
	}

	private void doRequest(boolean post, HttpServletRequest req, HttpServletResponse resp) throws ServletException,
			IOException {
		String streamIP = null;
		try {
			logger.trace("start " + req.getRemoteAddr());
			InputStream docxin = null;
			InputStream templatein = null;
			FileItem book = null;
			FileItem template = null;
			boolean translit = false;
			boolean useurl = false;
			boolean fontReport = false;
			String docxurl = null;
			File workPath = Initializer.getWorkDir();
			String ref = null;
			String lang = "en";
			workPath.mkdir();
			String docxpath = null;
			if (post && ServletFileUpload.isMultipartContent(req)) {
				DiskFileItemFactory itemFac = new DiskFileItemFactory();
				File repositoryPath = Initializer.getUploadDir();
				repositoryPath.mkdir();
				itemFac.setRepository(repositoryPath);
				ServletFileUpload servletFileUpload = new ServletFileUpload(itemFac);
				List fileItemList = servletFileUpload.parseRequest(req);
				Iterator list = fileItemList.iterator();
				while (list.hasNext()) {
					FileItem item = (FileItem) list.next();
					String t = item.getString();
					String paramName = item.getFieldName();
					if (paramName.equals("file")) {
						if (t.startsWith("http://"))
							docxurl = t;
						else if (t.length() > 0)
							book = item;
					} else if (paramName.equals("template")) {
						if (t.length() > 0)
							template = item;
					} else if (paramName.equals("translit"))
						translit = t.equals("on") || t.equals("yes");
					else if (paramName.equals("fontReport"))
						fontReport = t.equals("on") || t.equals("yes");
					else if (paramName.equals("useurl"))
						useurl = t.equals("on") || t.equals("yes");
					else if (paramName.equals("url")) {
						if (t.length() > 0)
							docxurl = t;
					} else if (paramName.equals("ref")) {
						ref = item.getString();
					} else if (paramName.equals("lang")) {
						lang = item.getString();
					}
				}
				if (!useurl && book != null) {
					docxin = book.getInputStream();
					docxpath = book.getName();
				}
				if (template != null)
					templatein = template.getInputStream();
			} else {
				docxurl = req.getParameter("url");
				String t = req.getParameter("translit");
				translit = t != null && (t.equals("on") || t.equals("yes"));
				t = req.getParameter("fontReport");
				fontReport = t != null && (t.equals("on") || t.equals("yes"));
				ref = req.getParameter("ref");
				lang = req.getParameter("lang");
			}
			if (docxin == null) {
				if (docxurl == null) {
					reportError(resp, "Invalid request: neither docx file nor URL is provided");
					return;
				}
				URL url = new URL(docxurl);
				if (!url.getProtocol().equals("http")) {
					reportError(resp, "Invalid request: docx URL protocol is not http");
					return;
				}
				docxpath = url.getPath();
				String host = url.getHost();
				InetAddress ipaddr = InetAddress.getByName(host);
				String ipstr = ipaddr.toString();
				synchronized (activeStreams) {
					if (!activeStreams.contains(ipstr)) {
						activeStreams.add(ipstr);
						streamIP = ipstr;
					}
				}
				if (streamIP == null) {
					reportError(resp, "Only a single connection to the server " + host + " is allowed");
					return;
				}
				logger.info("downloading from " + docxurl);
				docxin = url.openStream();
			}
			File docxtmp = File.createTempFile("docx2epub", "docx", workPath);
			FileOutputStream docxout = new FileOutputStream(docxtmp);
			byte[] buffer = new byte[8 * 1024];
			int len;
			while ((len = docxin.read(buffer)) > 0) {
				docxout.write(buffer, 0, len);
			}
			docxout.close();
			docxin.close();
			if (book != null)
				book.delete();
			if (docxpath != null) {
				if (docxpath.endsWith("/"))
					docxpath = docxpath.substring(0, docxpath.length() - 1);
				int index = docxpath.lastIndexOf('/');
				if (index >= 0)
					docxpath = docxpath.substring(index + 1);
				index = docxpath.lastIndexOf('\\');
				if (index >= 0)
					docxpath = docxpath.substring(index + 1);
			}
			WordDocument doc = new WordDocument(docxtmp);
			Publication epub = new Publication();
			epub.setTranslit(translit);
			epub.useAdobeFontMangling();
			DOCXConverter conv = new DOCXConverter(doc, epub);
			if (templatein != null) {
				if (template != null)
					template.delete();
			}
			FontLocator fontLocator = Initializer.getDefaultFontLocator();
			FontCookieSet customFontCookies = new FontCookieSet(req);
			SharedFontSet sharedFontSet = SharedFontSet.getInstance();
			fontLocator = sharedFontSet.getFontLocator(customFontCookies, fontLocator);
			conv.setFontLocator(fontLocator);
			conv.setWordResources(new ZipContainerSource(docxtmp));
			conv.convert();
			FontEmbeddingReport report = conv.embedFonts();
			if (fontReport) {
				FontsServlet.reportFonts(resp, report, ref, lang);
			} else {
				String title = epub.getDCMetadata("title");
				String fname;
				if (title == null) {
					if (docxpath != null) {
						fname = docxpath;
						if (fname.endsWith(".docx"))
							fname = fname.substring(0, fname.length() - 5);
						epub.addDCMetadata("title", docxpath);
					} else
						fname = "book";
				} else {
					fname = Translit.translit(title).replace(' ', '_').replace('\t', '_').replace('\n', '_').replace(
							'\r', '_');
				}
				resp.setContentType("application/epub+zip");
				resp.setHeader("Content-Disposition", "attachment; filename=" + fname + ".epub");
				OutputStream out = resp.getOutputStream();
				OCFContainerWriter container = new OCFContainerWriter(out);
				epub.serialize(container);
			}
			docxtmp.delete();
		} catch (Exception e) {
			logger.error("error", e);
			reportError(resp, "Internal server error: " + e.toString());
			e.printStackTrace();
		} catch (Throwable e) {
			logger.fatal("error", e);
		} finally {
			if (streamIP != null) {
				synchronized (activeStreams) {
					activeStreams.remove(streamIP);
				}
			}
			logger.trace("end");
		}
	}
}
