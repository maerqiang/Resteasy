package org.jboss.resteasy.core.registry;

import org.jboss.resteasy.core.ResourceInvoker;
import org.jboss.resteasy.specimpl.UriInfoImpl;
import org.jboss.resteasy.spi.Failure;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.LoggableFailure;
import org.jboss.resteasy.util.Encode;
import org.jboss.resteasy.util.HttpResponseCodes;
import org.jboss.resteasy.util.PathHelper;

import javax.ws.rs.core.PathSegment;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class PathParamSegment extends Segment
{
   protected String pathExpression;
   protected String regex;
   protected Pattern pattern;
   protected List<Group> groups = new ArrayList<Group>();

   private static class Group
   {
      int group;
      String name;
      boolean storePathSegment;

      private Group(int group, String name)
      {
         this.group = group;
         this.name = name;
      }

      private Group(int group, String name, boolean storePathSegment)
      {
         this.group = group;
         this.name = name;
         this.storePathSegment = storePathSegment;
      }
   }

   public static final Pattern GROUP = Pattern.compile("[^\\\\]\\(");

   /**
    * Find the number of groups int he regular expression
    * don't count escaped '('
    *
    * @param regex
    * @return
    */
   private static int groupCount(String regex)
   {
      Matcher matcher = GROUP.matcher(regex);
      int groupCount = 0;
      if (regex.startsWith("(")) groupCount++; // couldn't find a good regex to match start
      while (matcher.find()) groupCount++;
      return groupCount;
   }

   public PathParamSegment(String segment)
   {
      this.pathExpression = segment;
      String[] split = PathHelper.URI_PARAM_PATTERN.split(segment);
      Matcher withPathParam = PathHelper.URI_PARAM_PATTERN.matcher(segment);
      int i = 0;
      StringBuffer buffer = new StringBuffer();
      if (i < split.length) buffer.append(Pattern.quote(split[i++]));
      int groupNumber = 1;

      while (withPathParam.find())
      {
         String name = withPathParam.group(1);
         buffer.append("(");
         if (withPathParam.group(3) == null)
         {
            buffer.append("[^/]+");
            groups.add(new Group(groupNumber++, name, true));
         }
         else
         {
            String expr = withPathParam.group(3);
            buffer.append(expr);
            groups.add(new Group(groupNumber++, name));
            groupNumber += groupCount(expr);
         }
         buffer.append(")");
         if (i < split.length) buffer.append(Pattern.quote(split[i++]));
      }
      regex = buffer.toString();
      pattern = Pattern.compile(regex);
   }

   public String getRegex()
   {
      return regex;
   }

   public String getPathExpression()
   {
      return pathExpression;
   }

   protected void populatePathParams(HttpRequest request, Matcher matcher, String path)
   {
      UriInfoImpl uriInfo = (UriInfoImpl) request.getUri();
      for (Group group : groups)
      {
         String value = matcher.group(group.group);
         uriInfo.addEncodedPathParameter(group.name, value);
         int index = matcher.start(group.group);

         int start = 0;
         if (path.charAt(0) == '/') start++;
         int segmentIndex = 0;

         if (start < path.length())
         {
            int count = 0;
            for (int i = start; i < index && i < path.length(); i++)
            {
               if (path.charAt(i) == '/') count++;
            }
            segmentIndex = count;
         }

         int numSegments = 1;
         for (int i = 0; i < value.length(); i++)
         {
            if (value.charAt(i) == '/') numSegments++;
         }

         if (segmentIndex + numSegments > request.getUri().getPathSegments().size())
         {

            throw new LoggableFailure("Number of matched segments greater than actual", HttpResponseCodes.SC_INTERNAL_SERVER_ERROR);
         }
         PathSegment[] encodedSegments = new PathSegment[numSegments];
         PathSegment[] decodedSegments = new PathSegment[numSegments];
         for (int i = 0; i < numSegments; i++)
         {
            encodedSegments[i] = request.getUri().getPathSegments().get(segmentIndex + i);
            decodedSegments[i] = request.getUri().getPathSegments(false).get(segmentIndex + i);
         }
         uriInfo.getEncodedPathParameterPathSegments().add(group.name, encodedSegments);
         uriInfo.getPathParameterPathSegments().add(group.name, decodedSegments);
      }
   }

   public ResourceInvoker matchPattern
           (HttpRequest
                   request, String
                   path, int start)
   {
      UriInfoImpl uriInfo = (UriInfoImpl) request.getUri();
      Matcher matcher = pattern.matcher(path);
      matcher.region(start, path.length());

      if (matcher.matches())
      {
         // we consumed entire path string
         ResourceInvoker invoker = match(request.getHttpMethod(), request.getHttpHeaders().getMediaType(), request.getHttpHeaders().getAcceptableMediaTypes());
         if (invoker == null)
            throw new Failure("Could not find resource for: " + path, HttpResponseCodes.SC_NOT_FOUND);
         uriInfo.pushMatchedURI(path, Encode.decode(path));
         populatePathParams(request, matcher, path);
         return invoker;
      }
      if (locator == null)
      {
         throw new Failure("Could not find resource for: " + path, HttpResponseCodes.SC_NOT_FOUND);
      }
      if (matcher.find(start))
      {
         // a non-matched locator path must have a '/' immediately after.  A locator cannot match a partial segment
         if (path.charAt(start + matcher.group(0).length()) == '/')
         {
            String matched = path.substring(0, start + matcher.group(0).length());
            uriInfo.pushMatchedURI(matched, Encode.decode(matched));
            populatePathParams(request, matcher, path);
            return locator;
         }
      }
      throw new Failure("Could not find resource for: " + path, HttpResponseCodes.SC_NOT_FOUND);
   }

   public static int pathSegmentIndex
           (String
                   string, int start,
                           int stop)
   {
      if (start >= string.length()) return 0;
      int count = 0;
      for (int i = start; i < stop && i < string.length(); i++)
      {
         if (string.charAt(i) == '/') count++;
      }
      return count;
   }

}