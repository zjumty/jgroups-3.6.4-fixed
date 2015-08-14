
package org.jgroups.tests;


import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.View;
import org.jgroups.ViewId;
import org.jgroups.util.Util;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@Test(groups=Global.FUNCTIONAL)
public class ViewTest {
    protected Address       a, b, c, d, e, f, g, h, i;
    protected View          view;
    protected List<Address> members;
    

   

    @BeforeClass
    void setUp() throws Exception {
        a=Util.createRandomAddress("A");
        b=Util.createRandomAddress("B");
        c=Util.createRandomAddress("C");
        d=Util.createRandomAddress("D");
        e=Util.createRandomAddress("E");
        f=Util.createRandomAddress("F");
        g=Util.createRandomAddress("G");
        h=Util.createRandomAddress("H");
        i=Util.createRandomAddress("I");
        members=Arrays.asList(a, b, c, d, e, f, g, h);
        view=View.create(a, 34, a, b, c, d, e, f, g, h);

    }

    @Test(expectedExceptions=IllegalArgumentException.class)
    public void testConstructor() {
        view=new View(a, 1, null);
    }


    public void testGetMembers() throws Exception {
        List<Address> mbrs=view.getMembers();
        try {
            mbrs.add(a);
            assert false: "adding a member to a view should throw an exception";
        }
        catch(UnsupportedOperationException ex) {
            System.out.println("adding a member threw " + ex.getClass().getSimpleName() + " as expected");
        }

        byte[] buf=Util.objectToByteBuffer(view);
        View view2=(View)Util.objectFromByteBuffer(buf);
        System.out.println("view2 = " + view2);

        mbrs=view2.getMembers();
        try {
            mbrs.add(a);
            assert false: "adding a member to a view should throw an exception";
        }
        catch(UnsupportedOperationException ex) {
            System.out.println("adding a member threw " + ex.getClass().getSimpleName() + " as expected");
        }
    }

    public void testContainsMember() {
        assert view.containsMember(a) : "Member should be in view";
        assert view.containsMember(b) : "Member should be in view";
        assert view.containsMember(c) : "Member should be in view";
        assert view.containsMember(d) : "Member should be in view";
        assert view.containsMember(e) : "Member should be in view";
        assert view.containsMember(f) : "Member should be in view";
        assert !view.containsMember(i) : "Member should not be in view";
    }

    public void testEqualsCreator() {
        assert a.equals(view.getCreator()) : "Creator should be a";
        assert !view.getCreator().equals(d) : "Creator should not be d";
    }

    public void testEquals() {
        assert view.equals(view);
    }

    public void testEquals2() {
        View v1=new View(new ViewId(a, 12345), new ArrayList<>(members));
        View v2=new View(a, 12345, new ArrayList<>(members));
        assert v1.equals(v2);
        View v3=new View(a, 12543, new ArrayList<>(members));
        assert !v1.equals(v3);
    }
 

    public void testCopy() throws Exception {
        View view2=view;
        System.out.println("view = " + view);
        System.out.println("view2 = " + view2);
        assert view.equals(view2);

        List<Address> mbrs=view2.getMembers();
        try {
            mbrs.add(a);
            assert false: "adding a member to a view should throw an exception";
        }
        catch(UnsupportedOperationException ex) {
            System.out.println("adding a member threw " + ex.getClass().getSimpleName() + " as expected");
        }
    }


    public void testDiff() {
        View one=null;
        View two=View.create(a, 1, a, b, c);
        Address[][] diff=View.diff(one,two);
        System.out.println("diffs: " + printDiff(diff));
        Address[] joined=diff[0], left=diff[1];
        assert joined.length == 3;
        assert joined[0].equals(a) && joined[1].equals(b) && joined[2].equals(c);
        assert left.length == 0;
    }

    public void testDiff2() {
        View one=View.create(a, 1, a,b,c);
        View two=View.create(a, 2, a,b,c,d,e);
        Address[][] diff=View.diff(one,two);
        System.out.println("diffs: " + printDiff(diff));
        Address[] joined=diff[0], left=diff[1];
        assert joined.length == 2;
        assert joined[0].equals(d) && joined[1].equals(e);
        assert left.length == 0;
    }

    public void testDiff3() {
        View one=View.create(a, 1, a,b,c,d,e);
        View two=View.create(a, 2, a,b,c);
        Address[][] diff=View.diff(one,two);
        System.out.println("diffs: " + printDiff(diff));
        Address[] joined=diff[0], left=diff[1];
        assert joined.length == 0;
        assert left.length == 2;
        assert left[0].equals(d) && left[1].equals(e);
    }

    public void testDiff4() {
        View one=View.create(a, 1, a,b,c,d,e,f,g);
        View two=View.create(b, 2, b,c,d,g,h,i);
        Address[][] diff=View.diff(one,two);
        System.out.println("diffs: " + printDiff(diff));
        Address[] joined=diff[0], left=diff[1];
        assert joined.length == 2;
        assert joined[0].equals(h) && joined[1].equals(i);

        assert left.length == 3;
        assert left[0].equals(a) && left[1].equals(e) && left[2].equals(f);
    }


    public void testIterator() {
        List<Address> mbrs=new ArrayList<>(members.size());
        for(Address addr: view)
            mbrs.add(addr);

        System.out.println("mbrs: " + mbrs);
        Assert.assertEquals(members, mbrs);
    }

    protected static String printDiff(Address[][] diff) {
        StringBuilder sb=new StringBuilder();
        Address[] joined=diff[0], left=diff[1];
        sb.append("joined: ").append(Arrays.toString(joined)).append(", left: ").append(Arrays.toString(left));
        return sb.toString();
    }

}
