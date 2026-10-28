export const examples = {
  'Swap two values': `public class Main {
    public static void main(String[] args) {
        int[] arr = {12, 8, 24, 16, 5};

        int temp = arr[0];
        arr[0] = arr[3];
        arr[3] = temp;

        for (int i = 0; i < arr.length; i++) {
            System.out.println(arr[i]);
        }
    }
}`,
  'Reverse an array': `public class Main {
    public static void main(String[] args) {
        int[] arr = {3, 7, 11, 15, 19, 23};
        int left = 0;
        int right = arr.length - 1;
        while (left < right) {
            int temp = arr[left];
            arr[left] = arr[right];
            arr[right] = temp;
            left++;
            right--;
        }
        System.out.println(java.util.Arrays.toString(arr));
    }
}`,
  'Bubble sort': `public class Main {
    public static void main(String[] args) {
        int[] arr = {9, 3, 7, 1, 5};
        for (int i = 0; i < arr.length - 1; i++) {
            for (int j = 0; j < arr.length - i - 1; j++) {
                if (arr[j] > arr[j + 1]) {
                    int temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                }
            }
        }
        System.out.println(java.util.Arrays.toString(arr));
    }
}`,
  'Sum & traversal': `public class Main {
    public static void main(String[] args) {
        int[] numbers = {4, 8, 15, 16, 23, 42};
        int sum = 0;
        for (int i = 0; i < numbers.length; i++) {
            int current = numbers[i];
            sum += current;
        }
        System.out.println("Sum = " + sum);
    }
}`,
  'Methods & aliases': `public class Main {
    static void doubleValues(int[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] *= 2;
        }
    }
    public static void main(String[] args) {
        int[] arr = {2, 4, 6};
        int[] alias = arr;
        doubleValues(alias);
        System.out.println(java.util.Arrays.toString(arr));
    }
}`,
};
