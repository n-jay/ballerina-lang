import ballerina/module1;

function bar (string | int | boolean i)  returns (string | int | boolean) {
    string | int | boolean var1 =  "Test";
    [string, int, boolean] var2 = ["Foo", 12, true];
    int var4 = 12;
    string var5 = "test";
    Person p = { name : "Tom", age : 10};

    match module1:

    return 1;
}

type Person record {
    string name;
    int age;
};
