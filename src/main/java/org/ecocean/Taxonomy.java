package org.ecocean;

import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.ecocean.Util;
import javax.jdo.Query;
import java.util.Collection;

public class Taxonomy implements java.io.Serializable {

  private String id;

  // The scientific name is the authoritative name the scientific community uses to identify what is colloquially called "a species."
  // There should be only one record per scientificName value in the Taxonomy table, though they sometimes change e.g. when giraffes were reclassified.
  // usually "Genus species" or "Genus species subspecies"
  private String scientificName;
  private List<String> commonNames; 
  private Integer itisTsn;
    private boolean nonSpecific=false;

  // A Convention: getters/setters for Taxonomy objects (in other Classes) will use noun "Taxonomy".
  // while convenience string-only methods will use noun "Species" (and might require Shepherds to see which Taxonomy objects exist in the DB, for e.g. putting a species string on an Encounter)

  public Taxonomy() {
  }

  public Taxonomy(String scientificName) {
    this.id = Util.generateUUID();
    this.setScientificName(scientificName);
    this.commonNames = new ArrayList<String>();
  }

  public Taxonomy(String scientificName, String commonName) {
    this(scientificName);
    this.addCommonName(commonName);
  }

  public String getId() {
    return id;
  }


  public void setScientificName(String scientificName) {
    this.scientificName = scientificName;
  }
  public String getScientificName() {
    return scientificName;
  }

  public void setCommonNames(List<String> commonNames) {
    this.commonNames = commonNames;
  }
  public List<String> getCommonNames() {
    return commonNames;
  }
  public void addCommonName(String commonName) {
    if (!this.commonNames.contains(commonName)) this.commonNames.add(commonName);
  }
  public String getCommonName() {
    return getCommonName(0);
  }
  public String getCommonName(int i) {
    if (commonNames==null || commonNames.size()<=i) return null;
    return commonNames.get(i);
  }

    public Integer getItisTsn() {
        return itisTsn;
    }
    public void setItisTsn(Integer tsn) {
        itisTsn = tsn;
    }

    public boolean getNonSpecific() {
        return nonSpecific;
    }
    public void setNonSpecific(boolean b) {
        nonSpecific = b;
    }

    //note: we could also use scientific name?
    public boolean equals(final Object t2) {
        if (t2 == null) return false;
        if (!(t2 instanceof Taxonomy)) return false;
        Taxonomy two = (Taxonomy)t2;
        if ((this.id == null) || (two == null) || (two.getId() == null)) return false;
        return this.id.equals(two.getId());
    }
    public int hashCode() {  //we need this along with equals() for collections methods (contains etc) to work!!
        if (id == null) return Util.generateUUID().hashCode();  //random(ish) so we dont get two users with no uuid equals! :/
        return id.hashCode();
    }

    //really only for Encounter.  :(   someday this will go away! (plz)
    //  should be *no more than* two.... :(
    public String[] getGenusSpecificEpithet() {
        return Util.stringToGenusSpecificEpithet(this.scientificName);
    }

    public String toString() {
        return new ToStringBuilder(this)
                .append("id", id)
                .append("scientificName", scientificName)
                .toString();
    }

    //merges a "false" taxonomy, into an approved one.
    //skipCommonName will cause it *not* to add common name(s) of absorbed Taxonomy
    public int absorb(Taxonomy goAway, Shepherd myShepherd, boolean skipCommonNames) {
        if (goAway == null) return -1;
        if (!skipCommonNames && !Util.collectionIsEmptyOrNull(goAway.getCommonNames())) {
            for (String cn : goAway.getCommonNames()) {
                this.addCommonName(cn);
            }
        }
        List<Occurrence> occs = goAway.getOccurrences(myShepherd);
        if (Util.collectionIsEmptyOrNull(occs)) return 0;
        for (Occurrence occ : occs) {
            List<Taxonomy> newTaxs = new ArrayList<Taxonomy>();
            for (Taxonomy tx : occ.getTaxonomies()) {
                if (goAway.equals(tx)) {
                    newTaxs.add(this);
                } else {
                    newTaxs.add(tx);
                }
            }
            occ.setTaxonomies(newTaxs);
        }
        return occs.size();
    }

    public static List<Occurrence> getOccurrences(Shepherd myShepherd, Taxonomy tax) {
        if (tax == null) return null;
        String jdoql = "SELECT FROM org.ecocean.Occurrence WHERE taxonomies.contains(tx) && tx.id == '" + tax.getId() + "' VARIABLES org.ecocean.Taxonomy tx";
        Query query = myShepherd.getPM().newQuery(jdoql);
        Collection c = (Collection) (query.execute());
        List<Occurrence> occs = new ArrayList<Occurrence>(c);
        query.closeAll();
        return occs;
    }
    public List<Occurrence> getOccurrences(Shepherd myShepherd) {
        return getOccurrences(myShepherd, this);
    }

}
